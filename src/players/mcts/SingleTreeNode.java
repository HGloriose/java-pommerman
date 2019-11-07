package players.mcts;

import core.GameState;
import players.heuristics.AdvancedHeuristic;
import players.heuristics.CustomHeuristic;
import players.heuristics.StateHeuristic;
import utils.ElapsedCpuTimer;
import utils.Types;
import utils.Utils;
import utils.Vector2d;
import objects.GameObject;
import objects.Avatar;

import java.util.ArrayList;
import java.util.Random;
import java.util.Arrays;

public class SingleTreeNode
{
    public MCTSParams params;

    private SingleTreeNode parent;
    private SingleTreeNode[] children;
    private double totValue;
    private int nVisits;
    private Random m_rnd;
    private int m_depth;
    private double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE};
    private int childIdx;
    private int fmCallsCount;

    private int num_actions;
    private Types.ACTIONS[] actions;

    private GameState rootState;
    private StateHeuristic rootStateHeuristic;

    SingleTreeNode(MCTSParams p, Random rnd, int num_actions, Types.ACTIONS[] actions) { //root constructor
        this(p, null, -1, rnd, num_actions, actions, 0, null);
    }

    private SingleTreeNode(MCTSParams p, SingleTreeNode parent, int childIdx, Random rnd, int num_actions,
                           Types.ACTIONS[] actions, int fmCallsCount, StateHeuristic sh) { // not root constructor - stateHeuristics: evaluateState(GameState gs) depends on the heuristic used
        this.params = p;
        this.fmCallsCount = fmCallsCount;
        this.parent = parent;
        this.m_rnd = rnd;
        this.num_actions = num_actions;
        this.actions = actions;
        children = new SingleTreeNode[num_actions];
        totValue = 0.0;
        this.childIdx = childIdx;
        if(parent != null) {
            m_depth = parent.m_depth + 1;
            this.rootStateHeuristic = sh;
        }
        else
            m_depth = 0;
    }

    void setRootGameState(GameState gs)
    {
        this.rootState = gs;
        if (params.heuristic_method == params.CUSTOM_HEURISTIC)
            this.rootStateHeuristic = new CustomHeuristic(gs);
        else if (params.heuristic_method == params.ADVANCED_HEURISTIC) // New method: combined heuristics
            this.rootStateHeuristic = new AdvancedHeuristic(gs, m_rnd);
    }


    void mctsSearch(ElapsedCpuTimer elapsedTimer) {

        double avgTimeTaken;
        double acumTimeTaken = 0;
        long remaining;
        int numIters = 0;

        int remainingLimit = 5;
        boolean stop = false;

        while(!stop){

            GameState state = rootState.copy();
            ElapsedCpuTimer elapsedTimerIteration = new ElapsedCpuTimer();
            SingleTreeNode selected = treePolicy(state); //recomendation policy ? not UCB (in the code though) so selection & expansion
            double delta = selected.rollOut(state); //simulation
            backUp(selected, delta); //backpropagation

            //Stopping condition
            if(params.stop_type == params.STOP_TIME) { // they are always equal one another, unless update somewhere
                numIters++;
                acumTimeTaken += (elapsedTimerIteration.elapsedMillis()) ;
                avgTimeTaken  = acumTimeTaken/numIters;
                remaining = elapsedTimer.remainingTimeMillis();
                stop = remaining <= 2 * avgTimeTaken || remaining <= remainingLimit;
            }else if(params.stop_type == params.STOP_ITERATIONS) {
                numIters++;
                stop = numIters >= params.num_iterations;
            }else if(params.stop_type == params.STOP_FMCALLS)
            {
                fmCallsCount+=params.rollout_depth;
                stop = (fmCallsCount + params.rollout_depth) > params.num_fmcalls;
            }
            //System.out.println(" ITERS " + numIters);
        }

    }

    private SingleTreeNode treePolicy(GameState state) {

        SingleTreeNode cur = this;

        while (!state.isTerminal() && cur.m_depth < params.rollout_depth)
        {
            if (cur.notFullyExpanded()) {
                return cur.expand(state);

            } else {
                cur = cur.uct(state);
            }
        }

        return cur;
    }


    private SingleTreeNode expand(GameState state) {

        int bestAction = 0;
        double bestValue = -1;

        for (int i = 0; i < children.length; i++) {
            double x = m_rnd.nextDouble();
            if (x > bestValue && children[i] == null) {
                bestAction = i;
                bestValue = x;
            }
        }

        //Roll the state
        roll(state, actions[bestAction]);

        SingleTreeNode tn = new SingleTreeNode(params,this,bestAction,this.m_rnd,num_actions,
                actions, fmCallsCount, rootStateHeuristic);
        children[bestAction] = tn;
        return tn;
    }

    private void roll(GameState gs, Types.ACTIONS act)
    {
        //Simple, all random first, then my position.
        int nPlayers = 4;
        Types.ACTIONS[] actionsAll = new Types.ACTIONS[4];
        int playerId = gs.getPlayerId() - Types.TILETYPE.AGENT0.getKey();

        for(int i = 0; i < nPlayers; ++i)
        {
            if(playerId == i)
            {
                actionsAll[i] = act;
                //System.out.println("SingleTreeNode - MSCTPlayer: " + i + " Action: " + actionsAll[i]);
            }else {
                int actionIdx = m_rnd.nextInt(gs.nActions());
                //System.out.println("SingleTreeNode - Player: " + i + " Action: " + actionIdx);
                actionsAll[i] = Types.ACTIONS.all().get(actionIdx);
            }
        }

        //System.out.println("SingleTreeNode - actionsAll: " + actionsAll);
        gs.next(actionsAll);

    }

    private SingleTreeNode uct(GameState state) {
        SingleTreeNode selected = null;
        double bestValue = -Double.MAX_VALUE;
        for (SingleTreeNode child : this.children)
        {
            double hvVal = child.totValue;
            double childValue =  hvVal / (child.nVisits + params.epsilon); //

            childValue = Utils.normalise(childValue, bounds[0], bounds[1]); //- Q(s,a)

            double uctValue = childValue +
                    params.K * Math.sqrt(Math.log(this.nVisits + 1) / (child.nVisits + params.epsilon));

            uctValue = Utils.noise(uctValue, params.epsilon, this.m_rnd.nextDouble());     //break ties randomly

            // small sampleRandom numbers: break ties in unexpanded nodes
            if (uctValue > bestValue) {
                selected = child;
                bestValue = uctValue;
            }
        }
        if (selected == null)
        {
            throw new RuntimeException("Warning! returning null: " + bestValue + " : " + this.children.length + " " +
                    + bounds[0] + " " + bounds[1]);
        }

        //Roll the state:
        roll(state, actions[selected.childIdx]);

        return selected;
    }

    double [][] weight = {{ 2.19717309e+00,4.64529639e+00,2.77838425e+00,3.06246351e+00,
            7.41721097e-01,1.87122520e+00,1.45809931e+00,3.64424456e+00,
            2.23077307e+00,-1.52512944e-01},

            {-9.55801330e-02,-1.40246906e+00,-4.81308985e-01,-6.75002477e-01,
                    1.11072134e-01,-7.74992338e-01,7.93618695e-03,-1.56797015e+00,
                    2.58024242e-01,-1.49090382e-02},

            {-1.34868612e-01,-8.18542144e-01,-1.52756295e+00,-7.88978566e-01,
                    3.88948649e-02,3.35652343e-01,2.44020076e-01,-4.89691340e-01,
                    5.57369693e-01,2.47142935e-01},

            {-2.20120303e+00,-5.98006665e-01,-1.42887656e-01,-1.07052096e+00,
                    9.23005511e-01,-5.91510628e-01,-5.11005727e-01,1.43067703e-01,
                    5.29314016e-01,-7.95374392e-02},

            { 2.34478690e-01,-1.82627851e+00,-6.26624657e-01,-5.27961511e-01,
                    3.13174159e-02,-8.40374573e-01,-1.19904985e+00,-1.72965078e+00,
                    8.86065121e-01,-1.83513696e-04}};

    private double rollOut(GameState state)
    {
        // HERE you change how the next action for MCTS is chosen
        int thisDepth = this.m_depth;


        int gsArray [][];
        gsArray = state.toArray();
        int size = state.getBoard().length;
        float sqrSize = size*size;

        int playerId = state.getPlayerId() - Types.TILETYPE.AGENT0.getKey();

        GameObject agents [];
        agents = state.getAgents();
        Avatar av = (Avatar) agents[state.getPlayerId()];
        Vector2d avatarPosition = av.getPosition();
        String [] tempAvPosition = (avatarPosition.toString().replace(" : ",",")).split(",");

        float squarePositionFraction = ((Float.parseFloat(tempAvPosition[0])+1 * size) - (size - Float.parseFloat(tempAvPosition[1])))/(sqrSize);

        int newGsSize = 3;

        int z = newGsSize;
        int zvert = newGsSize;
        int minhorizontal = Integer.parseInt(tempAvPosition[0]) - z;
        int minvertical = Integer.parseInt(tempAvPosition[1]) - z;
        while (z >= 0) {
            if (minhorizontal >= 0) {
                break;
            } else {
                z--;
                minhorizontal++;
            }
        }
        while (zvert >= 0) {
            if (minvertical >= 0) {
                break;
            } else {
                zvert--;
                minvertical++;
            }
        }
        int xrange = z + 1 + 2;
        int yrange = zvert + 1 + 2;

        while (xrange > 2+1) {
            xrange--;
        }
        while (yrange > 2+1) {
            yrange--;
        }

        int start2 [] = new int [2];
        int xyrange2 [] = new int [2];
        float gsSize2 [][];
        float flat2 [];

        start2[0] = minhorizontal;
        start2[1] = minvertical;
        xyrange2[0] = xrange;
        xyrange2[1] = yrange;

        gsSize2 = new float[xrange][yrange];
        for (int startH = 0; startH < xyrange2[0]; startH++) {
            for (int startV = 0; startV < xyrange2[1]; startV++) {
                gsSize2[startH][startV] = gsArray[minhorizontal + startH][minvertical + startV];
            }
        }
        flat2 = new float[gsSize2[0].length * gsSize2[1].length+1];
        int index = 0;
        for (int x = 0; x < gsSize2[0].length; x++) {
            for (int yy = 0; yy < gsSize2[1].length; yy++) {
                flat2[index] = gsArray[x][yy];
                index++;
            }
            flat2[gsSize2[0].length * gsSize2[1].length] = squarePositionFraction;
        }

        double [] results = new double[5];
        // Matrix multiplication
         for(int i = 0; i<weight[0].length; i++){
             int j =0;
             results[i] = 0;
             while(j<weight[1].length){
                 for(int k =0; k<flat2.length; k++){
                     results[i] += weight[i][j] * flat2[k];
                     j++;
                 }
             }
             results[i] = Math.exp(results[i]);
         }

         double sum = 0.0;
         for (int i = 0; i<results.length; i++){
             sum+= results[i];
         }

         double [] probabilities = new double[5];

         double maxProb = 0;
         int maxProbindex = 0;

         for(int i = 0; i<results.length; i++){
             probabilities[i] = results[i]/sum;
             if(probabilities[i] >= maxProb){
                 maxProb = probabilities[i];
                 maxProbindex = i;
             }
         }

        //String [] actionListLearned = {"ACTION_BOMB","ACTION_DOWN","ACTION_LEFT","ACTION_RIGHT","ACTION_UP"};
        int [] actionListLearnedInx = {5,2,3,4,1};
         int bestAction = actionListLearnedInx[maxProbindex];


         // need to pass on the result
        while (!finishRollout(state,thisDepth)) {
            //int action = safeRandomAction(state);
            //System.out.println("safeRandomAction(state): " + action);

            roll(state, actions[bestAction]);
            thisDepth++;
        }

        return rootStateHeuristic.evaluateState(state);
    }

    private int safeRandomAction(GameState state)
    {
        Types.TILETYPE[][] board = state.getBoard();
        ArrayList<Types.ACTIONS> actionsToTry = Types.ACTIONS.all();
        int width = board.length;
        int height = board[0].length;

        while(actionsToTry.size() > 0) {

            int nAction = m_rnd.nextInt(actionsToTry.size());
            Types.ACTIONS act = actionsToTry.get(nAction);
            Vector2d dir = act.getDirection().toVec();

            Vector2d pos = state.getPosition();
            int x = pos.x + dir.x;
            int y = pos.y + dir.y;

            if (x >= 0 && x < width && y >= 0 && y < height)
                if(board[y][x] != Types.TILETYPE.FLAMES)
                    return nAction;

            actionsToTry.remove(nAction);
        }

        //Uh oh...
        return m_rnd.nextInt(num_actions);
    }

    @SuppressWarnings("RedundantIfStatement")
    private boolean finishRollout(GameState rollerState, int depth)
    {
        if (depth >= params.rollout_depth)      //rollout end condition.
            return true;

        if (rollerState.isTerminal())               //end of game
            return true;

        return false;
    }

    private void backUp(SingleTreeNode node, double result)
    {
        SingleTreeNode n = node;
        while(n != null)
        {
            n.nVisits++;
            n.totValue += result;
            if (result < n.bounds[0]) {
                n.bounds[0] = result;
            }
            if (result > n.bounds[1]) {
                n.bounds[1] = result;
            }
            n = n.parent;
        }
    }


    int mostVisitedAction() {
        int selected = -1;
        double bestValue = -Double.MAX_VALUE;
        boolean allEqual = true;
        double first = -1;

        for (int i=0; i<children.length; i++) {

            if(children[i] != null)
            {
                if(first == -1)
                    first = children[i].nVisits;
                else if(first != children[i].nVisits)
                {
                    allEqual = false;
                }

                double childValue = children[i].nVisits;
                childValue = Utils.noise(childValue, params.epsilon, this.m_rnd.nextDouble());     //break ties randomly
                if (childValue > bestValue) {
                    bestValue = childValue;
                    selected = i;
                }
            }
        }

        if (selected == -1)
        {
            selected = 0;
        }else if(allEqual)
        {
            //If all are equal, we opt to choose for the one with the best Q.
            selected = bestAction();
        }

        return selected;
    }

    private int bestAction() //recommendation policy
    {
        int selected = -1;
        double bestValue = -Double.MAX_VALUE;

        for (int i=0; i<children.length; i++) {

            if(children[i] != null) {
                double childValue = children[i].totValue / (children[i].nVisits + params.epsilon);
                childValue = Utils.noise(childValue, params.epsilon, this.m_rnd.nextDouble());     //break ties randomly
                if (childValue > bestValue) {
                    bestValue = childValue;
                    selected = i;
                }
            }
        }

        if (selected == -1)
        {
            System.out.println("Unexpected selection!");
            selected = 0;
        }

        return selected;
    }


    private boolean notFullyExpanded() {
        for (SingleTreeNode tn : children) {
            if (tn == null) {
                return true;
            }
        }

        return false;
    }
}
