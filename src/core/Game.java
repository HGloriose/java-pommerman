package core;

import objects.Avatar;
import objects.GameObject;
import players.Player;
import players.SimonSaysPlayer;
import utils.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import static utils.Types.*;
import static utils.Types.VISUALS;

@SuppressWarnings("FieldCanBeLocal")
public class Game {

     // State of the game (objects, ticks, etc).
    private GameLog gameLog;

    // State of the game (objects, ticks, etc).
    private GameState gs;

    // GameState objects for players to make decisions
    private GameState[] gameStateObservations;

    // List of players of the game
    private ArrayList<Player> players;

    // Mode of the game being played. This could be FFA, TEAM or TEAM_RADIO.
    private Types.GAME_MODE gameMode;

    // Seed for the game state.
    private long seed;

    // Size of the board.
    private int size;

    // String that identifies this game (for logging purposes)
    private String gameIdStr;

    // Log flags
    public static boolean LOG_GAME = false;
    public static boolean LOG_GAME_JSON = false; // If the game is being logged, should it be saved to json

    // Variables for multi-threaded run 
    private Actor[] actors = new Actor[NUM_PLAYERS];
    private Thread[] threads = new Thread[NUM_PLAYERS];

    //Counts how many time a player did overtime
    private int[] playerOvertimes = new int[NUM_PLAYERS];

    /**
     * Constructor of the game
     * @param seed Seed for the game (used only for board generation)
     * @param size Size of the board.
     * @param gameMode Mode of this game.
     */
    public Game(long seed, int size, Types.GAME_MODE gameMode, String gameIdStr) {
        this.gameMode = gameMode;
        this.seed = seed;
        this.size = size;
        this.gameIdStr = gameIdStr;
        reset(seed);
    }

    /**
     * Optional game constructor
     * @param seed Seed for the game
     * @param state Starting game state
     * @param gameMode Mode of this game
     */
    public Game(long seed, GameState state, Types.GAME_MODE gameMode) {
        this.gameMode = gameMode;
        this.seed = seed;
        this.gs = state.copy();
        this.gs.model.setTrueModel();
        this.size = state.model.getBoard().length;
        updateAssignedGameStates();
    }

    /**
     * Optional game constructor
     * @param seed Seed for the game
     * @param model Starting forward model
     * @param gameMode Mode of this game
     */
    public Game(long seed, ForwardModel model, Types.GAME_MODE gameMode) {
        this.gameMode = gameMode;
        this.seed = seed;
        this.size = model.getBoard().length;
        this.gs = new GameState(seed, model, gameMode);
        this.gs.model.setTrueModel();
        updateAssignedGameStates();
    }

    /**
     * Optional game constructor
     * @param gs Starting game state
     */
    public Game(GameState gs) {
        this.gs = gs.copy();
        this.gameMode = gs.gameMode;
        this.seed = gs.getSeed();
        this.size = gs.model.getBoard().length;
        this.gs.model.setTrueModel();
        updateAssignedGameStates();
    }

    /**
     * Resets the game to its initial state
     * @param seed new seed for the game;
     */
    public void reset(long seed)
    {
        this.seed = seed;
        this.gs = new GameState(seed, size, gameMode, true);
        this.gs.model.setTrueModel();
        this.gs.init();
        updateAssignedGameStates();
    }

    /**
     * Resets the game to its initial state
     * @param sameBoard true if the same board should be played.
     */
    public void reset(boolean sameBoard)
    {
        if (!sameBoard) {
            this.seed = System.currentTimeMillis();
        }
        this.gs = new GameState(seed, size, gameMode, true);
        this.gs.model.setTrueModel();
        this.gs.init();
        updateAssignedGameStates();
    }

    /**
     * @return an exact copy of this game, including players, current game state and state observations.
     */
    public Game copy() {
        Game copy = new Game(gs);
        ArrayList<Player> copyPlayers = new ArrayList<>(this.players.size());
        for (Player player : players) {
            copyPlayers.add(player.copy());
        }
        copy.players = copyPlayers;
        copy.gameStateObservations = new GameState[gameStateObservations.length];
        for (int i = 0; i < gameStateObservations.length; i++) {
            if (gameStateObservations[i] != null)
                copy.gameStateObservations[i] = gameStateObservations[i].copy();
        }
        if (gameLog != null)
            copy.gameLog = gameLog.copy();
        return copy;
    }

    /**
     * Sets the players of the game and initializes the array to hold their game states.
     * @param players Players of the game.
     */
    public void setPlayers(ArrayList<Player> players) {
        this.players = players;
    }

    /**
     * Retuns the players of the game
     * @return the players of the game
     */
    public ArrayList<Player> getPlayers() {
        return players;
    }

    /**
     * Returns the game state as seen for the player with the index playerIdx. This game staet
     * includes only the observations that are visible if partial observability is enabled.
     * @param playerIdx index of the player for which the game state is generated.
     * @return the game state.
     */
    private GameState getGameState(int playerIdx) {
        return gs.copy(playerIdx);
    }

    /**
     * Runs this game once, without visuals
     * @return the results of this game.
     */
    public Types.RESULT[] run(boolean separateThreads)
    {
        return this.run(null, null, separateThreads);
    }

    /**
     * Runs a game once. Receives frame and window input. If any is null, forces a run with no visuals.
     * @param frame window to draw the game
     * @param wi input for the window.
     * @return the results of the game, per player.
     */

    ArrayList<String> toSaveGs = new ArrayList<String>();
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String json;
    String JSON_GAMELOGS_PATH = "res/gamelogs/";
    int REP = 0;

    public Types.RESULT[] run(GUI frame, WindowInput wi, boolean separateThreads)
    {
        if (frame == null || wi == null)
            VISUALS = false;

        boolean firstEnd = true;
        Types.RESULT[] results = null;
        if (LOG_GAME)
            gameLog = new GameLog(seed, size, gameMode);

        if (separateThreads) {
            createActors();
        }

        while(!isEnded() || VISUALS && wi != null && !wi.windowClosed && !isEnded()) {
            // Loop while window is still open, even if the game ended.
            // If not playing with visuals, loop while the game's not ended.
            tick(separateThreads);

            // Check end of game
            if (firstEnd && isEnded()) {
                firstEnd = false;
                results = terminate();

                int numPlayers = NUM_PLAYERS; // we are by default using 4 players
                for (int pIdx = 0; pIdx < numPlayers; pIdx++) {
                    String playerId;
                    Player p = players.get(pIdx);
                    switch (results[pIdx]) {
                        case WIN:
                            break;
                        case TIE:
//                            playerId = p.toString();
//                            //System.out.println("playerId: " + playerId);
//                            toSaveGs.removeIf(x -> x.startsWith(playerId));
                            break;
                        case LOSS:
                            playerId = p.toString();
                            //System.out.println("playerId: " + playerId);
                            toSaveGs.removeIf(x -> x.startsWith(playerId));
                            break;
                    }
                }

                toSaveGs.removeIf(x -> x.contains("ACTION_STOP"));

                System.out.println(toSaveGs);

                File file = new File(JSON_GAMELOGS_PATH + gameIdStr + "/");
                if (! file.exists()){
                    file.mkdirs();
                }
                if (file.listFiles() == null) {
                    throw new Error("Folder specified at "+ JSON_GAMELOGS_PATH +" does not exist nor could be created.");
                }
                String textPath = JSON_GAMELOGS_PATH  + gameIdStr + "/" + seed + "_"+ REP +"_"+  gameMode.name() + "["+size+"x"+size+"].txt";

                try {
                    PrintWriter textOut = new PrintWriter(textPath);
                    textOut.println(toSaveGs.toString());
                    textOut.close();
                } catch (IOException i) {
                i.printStackTrace();

                }


                if (!VISUALS) {
                    // The game has ended, end the loop if we're running without visuals.
                    break;
                }
            }

            // Paint game state
            if (VISUALS && frame != null) {
                frame.paint();
                try {
                    Thread.sleep(FRAME_DELAY);
                } catch (Exception e) {
                    System.out.println("EXCEPTION " + e);
                }
            }
        }

        // The loop may have been broken out of before the game ended. Handle end-of-game:
        if (firstEnd) {
            results = terminate();
        }

        // Save logged game
        if (LOG_GAME) {
            if (LOG_GAME_JSON) {
                gameLog.serializeJSON(gameIdStr);
                System.out.println("gameLog: " + gameLog);

            } else {
                gameLog.serialize();
            }
        }

        // Collect and kill all threads
        if (separateThreads) {
            try {
                killThreads();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return results;
    }

    /**
     * Ticks the game forward. Asks agents for actions and applies returned actions to obtain the next game state.
     * @param separateThreads - true if game should be run in separate threads, false otherwise.
     */
    void tick (boolean separateThreads) {
        if (VERBOSE) {
            System.out.println("tick: " + gs.getTick());
        }

        // Retrieve agent actions
        Types.ACTIONS[] actions = null;
        if (separateThreads) {
            try {
                actions = getAvatarActionsInSeparateThreads();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            actions = getAvatarActions();
        }

        // Log actions
        if (LOG_GAME) {
            gameLog.addActions(actions);
        }

        // Advance the game state
        gs.next(actions);
        updateMessages();
        updateAssignedGameStates();

        if (VERBOSE) {
            printBoard();
        }
    }

    /**
     * Get player actions, 1 for each avatar still in the game. Called at every frame.
     */
    private Types.ACTIONS[] getAvatarActions() {
        String tempString;
        // Get player actions, 1 for each avatar still in the game
        Types.ACTIONS[] actions = new Types.ACTIONS[NUM_PLAYERS];
        for (int i = 0; i < NUM_PLAYERS; i++) {
            tempString = "";
            Player p = players.get(i);

            GameObject agents [];
            agents = gs.getAgents();
            Avatar av = (Avatar) agents[i];
            Vector2d avatarPosition = av.getPosition();
            String [] tempAvPosition = (avatarPosition.toString().replace(" : ",",")).split(",");
            String avPosition= Arrays.toString(tempAvPosition);

            int gsArray [][];
            gsArray = gs.model.toArray();

            int boardSize = gs.getBoard().length;

            // position to start from  1 instead of 0 - so 1 to 12
            float squarePositionFraction = ((Float.parseFloat(tempAvPosition[1]+1) * size) - (size - Float.parseFloat(tempAvPosition[0]+1)))/121;

//            float gsArraywithPosition [][] = new float [size][size];
//            for (int x = 0; x < boardSize; x++) {
//                for (int y = 0; y < boardSize; y++) {
//                    gsArraywithPosition[x][y] = gsArray[x][y]*squarePositionFraction;
//                    //System.out.println("squarePositionFraction: " + squarePositionFraction);
//                    //System.out.println("gsArraywithPosition[x][y]: " +  gsArraywithPosition[x][y]);
//                }
//            }

            float flatGameState [] = new float[boardSize * boardSize + 1];
            int index = 0;
            for (int x = 0; x < boardSize; x++) {
                for (int y = 0; y < boardSize; y++) {
                    flatGameState[index] = gsArray[x][y];
                    index++;
                }
                flatGameState[boardSize * boardSize] = squarePositionFraction;
            }


            int xyrange5 [] = new int [2];
            int xyrange6 [] = new int [2];
            int xyrange7 [] = new int [2];
            int xyrange8 [] = new int [2];


            int start5 [] = new int [2];
            int start6 [] = new int [2];
            int start7 [] = new int [2];
            int start8 [] = new int [2];

            float gsSize5 [][];
            float gsSize6 [][];
            float gsSize7 [][];
            float gsSize8 [][];

            for (int y = 5; y < 9; y++) {
                int z = y;
                int zvert = y;
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
                int xrange = z + 1 + y;
                int yrange = zvert + 1 + y;

                while (xrange > y+1) {
                    xrange--;
                }
                while (yrange > y+1) {
                    yrange--;
                }

                float flat5 [];
                float flat6 [];
                float flat7 [];
                float flat8 [];

                //System.out.println("for dimension " + y+ ", xrange = " + xrange + ", yrange = " + yrange +  ", (minhorizontal, minvertical) = (" + minhorizontal + ", " + minvertical +")" );
                switch (y) {

                    case 5: {
                        start5[0] = minhorizontal;
                        start5[1] = minvertical;
                        xyrange5[0] = xrange;
                        xyrange5[1] = yrange;
                        gsSize5 = new float[xrange][yrange];
                        for (int startH = 0; startH < xyrange5[0]; startH++) {
                            for (int startV = 0; startV < xyrange5[1]; startV++) {
                                gsSize5[startH][startV] = gsArray[minhorizontal + startH][minvertical + startV];
                            }
                        }
                        flat5 = new float[gsSize5[0].length * gsSize5[1].length+1];
                        index = 0;
                        for (int x = 0; x < gsSize5[0].length; x++) {
                            for (int yy = 0; yy < gsSize5[1].length; yy++) {
                                flat5[index] = gsArray[x][yy];
                                index++;
                            }
                            flat5[gsSize5[0].length * gsSize5[1].length] = squarePositionFraction;
                        }
                        //System.out.println("gsSiz5: " + Arrays.deepToString(gsSize5));
                        break;
                    }

                    case 6: {
                        start6[0] = minhorizontal;
                        start6[1] = minvertical;
                        xyrange6[0] = xrange;
                        xyrange6[1] = yrange;
                        gsSize6 = new float[xrange][yrange];
                        for (int startH = 0; startH < xyrange6[0]; startH++) {
                            for (int startV = 0; startV < xyrange6[1]; startV++) {
                                gsSize6[startH][startV] = gsArray[minhorizontal + startH][minvertical + startV];
                            }
                        }
                        flat6 = new float[gsSize6[0].length * gsSize6[1].length+1];
                        index = 0;
                        for (int x = 0; x < gsSize6[0].length; x++) {
                            for (int yy = 0; yy < gsSize6[1].length; yy++) {
                                flat6[index] = gsArray[x][yy];
                                index++;
                            }
                            flat6[gsSize6[0].length * gsSize6[1].length] = squarePositionFraction;
                        }
                        //System.out.println("gsSiz6: " + Arrays.deepToString(gsSize6));
                        break;
                    }

                    case 7: {
                        start7[0] = minhorizontal;
                        start7[1] = minvertical;
                        xyrange7[0] = xrange;
                        xyrange7[1] = yrange;
                        gsSize7 = new float[xrange][yrange];
                        for (int startH = 0; startH < xyrange7[0]; startH++) {
                            for (int startV = 0; startV < xyrange7[1]; startV++) {
                                gsSize7[startH][startV] = gsArray[minhorizontal + startH][minvertical + startV];
                            }
                        }

                        flat7 = new float[gsSize7[0].length * gsSize7[1].length+1];
                        index = 0;
                        for (int x = 0; x < gsSize7[0].length; x++) {
                            for (int yy = 0; yy < gsSize7[1].length; yy++) {
                                flat7[index] = gsArray[x][yy];
                                index++;
                            }
                            flat7[gsSize7[0].length * gsSize7[1].length] = squarePositionFraction;
                        }

                        //System.out.println("flat7: " + Arrays.toString(flat7));
                        break;
                    }
                    case 8: {
                        start8[0] = minhorizontal;
                        start8[1] = minvertical;
                        xyrange8[0] = xrange;
                        xyrange8[1] = yrange;
                        gsSize8 = new float[xrange][yrange];
                        for (int startH = 0; startH < xyrange8[0]; startH++) {
                            for (int startV = 0; startV < xyrange8[1]; startV++) {
                                gsSize8[startH][startV] = gsArray[minhorizontal + startH][minvertical + startV];
                            }
                        }
                        flat8 = new float[gsSize8[0].length * gsSize8[1].length+1];
                        index = 0;
                        for (int x = 0; x < gsSize8[0].length; x++) {
                            for (int yy = 0; yy < gsSize8[1].length; yy++) {
                                flat8[index] = gsArray[x][yy];
                                index++;
                            }
                            flat8[gsSize8[0].length * gsSize8[1].length] = squarePositionFraction;
                        }
                        //System.out.println("gsSize8: " + Arrays.deepToString(gsSize8));
                        break;
                    }
                }
            }

            tempString = tempString + p.toString() + "\t" + Arrays.toString(flatGameState)  + "\t";

            // Check if this player is still playing
            if (gameStateObservations[i].winner() == Types.RESULT.INCOMPLETE) {

                ElapsedCpuTimer ect = new ElapsedCpuTimer();
                ect.setMaxTimeMillis(Types.DECISION_TIME_LIMIT);

                actions[i] = p.act(gameStateObservations[i]);

                long elapsedTime = ect.elapsedMillis();
                if(CHECK_DECISION_TIME && elapsedTime > DECISION_TIME_LIMIT)
                {
                    if(VERBOSE)
                        System.out.println("Player " + p.getPlayerID() + " used more time than allowed (" + elapsedTime + "ms). Executing action STOP.");
                    actions[i] = ACTIONS.ACTION_STOP;
                    playerOvertimes[i]++;
                }


            } else {
                // This player is dead and action will be ignored
                actions[i] = Types.ACTIONS.ACTION_STOP;
            }

            //System.out.println("Player: " + p.getPlayerID() + " " + actions[p.getPlayerID()] + "\n" +  gs.toString()+ "\n");
            //gs.model.toArray();
            //System.out.println("Player: " + p.getPlayerID() + " " + actions[p.getPlayerID()] + "\n" + gs.model.toArray().toString()+ " " + "\n");
            tempString = tempString + actions[i] + "\n"; // + "\t" + gs.model.toArray().toString();
            //System.out.println("PlayerID: " + p.getPlayerID() + "\n Player Position: " + avPosition + "\n Game State: " + Arrays.toString(flatGameState)+  "\n Player Action: " + actions[p.getPlayerID()] + "\n");
            toSaveGs.add(tempString);
        }
        //System.out.println(gs.toString());
        return actions;
    }

    /**
     * Get player actions, 1 for each avatar still in the game, using separate threads. Called at every frame.
     */
    private Types.ACTIONS[] getAvatarActionsInSeparateThreads() throws InterruptedException {
        //
        Types.ACTIONS[] actions = new Types.ACTIONS[NUM_PLAYERS];
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Player p = players.get(i);

            // Check if this player is still playing
            if (gameStateObservations[i].winner() == Types.RESULT.INCOMPLETE) {
                actors[i].player = p;
                threads[i] = new Thread(actors[i]);
                threads[i].start();
            } else {
                threads[i] = null;
                // This player is dead and action will be ignored
                actions[i] = Types.ACTIONS.ACTION_STOP;
            }
        }

        killThreads();

        for (int i = 0; i < NUM_PLAYERS; i++) {
            if (threads[i] != null)
                actions[i] = actors[i].getValue();
        }

        return actions;
    }

    /**
     * Creates actor objects for the players.
     */
    public void createActors() {
        for (int i = 0; i < NUM_PLAYERS; i++)
            actors[i] = new Actor();
    }

    /**
     * Kills all threads.
     * @throws InterruptedException if threads could not be killed
     */
    private void killThreads() throws InterruptedException {
        for (int i = 0; i < NUM_PLAYERS; i++) {
            if (threads[i] != null && threads[i].isAlive())
                threads[i].join();
        }
    }

    /**
     * Updates the state observations for all players.
     */
    private void updateAssignedGameStates() {
        if (gameStateObservations == null) {
            gameStateObservations = new GameState[NUM_PLAYERS];
        }
        for (int i = 0; i < NUM_PLAYERS; i++) {
            gameStateObservations[i] = getGameState(i);
        }
    }

    /**
     * Method to identify the end of the game.
     * All games end when maximum number of game ticks have been reached. If not:
     *   - In FFA, the game ends when <= 1 avatar is left
     *   - In TEAM and TEAM_RADIO, the game ends when only avatars of one team are left
     * @return true if the game has ended, false otherwise.
     */
    boolean isEnded() {
        //Delegate to our game config
        return getGameConfig().isEnded(gs.getTick(), gameMode, gs.getAliveAgents());
    }

    /**
     * handle messages for update, swap teammate's messages
     */
    protected void updateMessages() {
        if (gameMode.equals(GAME_MODE.TEAM_RADIO)){
            for (int i = 0; i < NUM_PLAYERS; i++) {
                int teammateIdx = getGameConfig().getTeammates(GAME_MODE.TEAM_RADIO, i + TILETYPE.AGENT0.getKey())[0].getKey() - TILETYPE.AGENT0.getKey();
                if (gameStateObservations[teammateIdx].winner() == RESULT.INCOMPLETE)
                    gs.setMessage(i, players.get(teammateIdx).getMessage());
                else
                    gs.setMessage(i, new int[MESSAGE_LENGTH]); // default case
            }
        }
    }

    /**
     * This method terminates the game, assigning the winner/result state to all players.
     * @return an array of result states for all players.
     */
    @SuppressWarnings("UnusedReturnValue")
    private Types.RESULT[] terminate() {
        //Build the results array
        GameObject[] agents = gs.getAgents();
        Types.RESULT[] results = new Types.RESULT[NUM_PLAYERS];
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Avatar av = (Avatar) agents[i];
            results[i] = av.getWinner();
        }

        // Call all agents' end-of-game method for post-processing. Agents receive their final reward.
        double[] finalRewards = getGameConfig().getRewards(getTick(), results);
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Player p = players.get(i);
            p.result(finalRewards[i]);
        }

        if (LOGGING_STATISTICS)
            gs.model.saveEventsStatistics(gameIdStr, seed);

//        if (VERBOSE) {
//        System.out.println("GameOver: " + Arrays.toString(results));
//        System.out.println(Arrays.toString(results));

        System.out.print("[");
        for(int i = 0; i < results.length; ++i)
        {
            System.out.print(results[i] + (" (" + playerOvertimes[i] + ")"));
            if(i == results.length-1)
                System.out.println("]");
            else
                System.out.print(", ");
        }

//        }
        return results;
    }

    /**
     * Prints the board to console.
     */
    void printBoard(){
        System.out.println(gs);
    }

    /**
     * Returns the board of the game, in the format of a bidimensional array where each
     * position includes a game object that occupies it. If partial observability is enabled,
     * the method hides that information that the player (pIdx) can't access.
     * @param pIdx Player index that this board is made for.
     * @return board of the game
     */
    public Types.TILETYPE[][] getBoard(int pIdx) {
        if (pIdx >= 0 && gameStateObservations[pIdx] != null) {
            return gameStateObservations[pIdx].getBoard();
        }
        return gs.getBoard();
    }

    /**
     * Returns the current tick of the game.
     * @return the current tick of the game.
     */
    public int getTick() {
        return gs.getTick();
    }

    /**
     * Retuns the avatars that are still alive in the game.
     * @param pIdx Index of the player this information is for.
     * @return An array with all avatars that still alive.
     */
    public ArrayList<GameObject> getAliveAvatars(int pIdx) {
        if (pIdx >= 0 && gameStateObservations[pIdx] != null) {
            return gameStateObservations[pIdx].model.getAliveAgents();
        }
        return gs.model.getAliveAgents();
    }

    /**
     * Returns number of players in the game
     * @return number of players in the game
     */
    public int nPlayers() {
        return NUM_PLAYERS;
    }

    /**
     * Returns the game mode of this game.
     * @return the game mode.
     */
    public Types.GAME_MODE getGameMode() {
        return gameMode;
    }

    /**
     * Returns all avatars of this game (dead or alive)
     * @param pIdx Index of the player this information is for.
     * @return array with all avatars of this game
     */
    public GameObject[] getAvatars(int pIdx) {
        if (pIdx >= 0 && gameStateObservations[pIdx] != null) {
            return gameStateObservations[pIdx].model.getAgents();
        }
        return gs.model.getAgents();
    }

    /**
     * @return the current game state.
     */
    public GameState getGameState() {
        return gs;
    }

    /**
     * Set up logging for the game.
     * @param b - if the game should be logged or not.
     */
    public void setLogGame(boolean b) {
        if (b && gameLog == null) {
            gameLog = new GameLog(seed, size, gameMode);
        }
        LOG_GAME = b;
    }

    /**
     * @return true if this game is being logged, false otherwise.
     */
    public boolean isLogged() {
        return LOG_GAME;
    }

    /**
     * Returns the last game logged, with SimonSays players executing the logged action sequences, the saved seed,
     * initial state and game mode.
     * @return - last game logged.
     */
    public static Game getLastReplayGame(){
        GameLog lastLog;
        if (Game.LOG_GAME_JSON) {
            lastLog = GameLog.deserializeLastJSON();
        } else {
            lastLog = GameLog.deserializeLast();
        }

        return logToGame(lastLog);
    }

    public Game getReplayGame(){
        return logToGame(gameLog);
    }

    private static Game logToGame(GameLog log){
        Game game = null;
        if (log != null) {
            game = new Game(log.getSeed(), log.getStartingGameState(), log.getGameMode());
            game.setLogGame(false);

            Queue<ACTIONS> p1actionsQueue = new ArrayDeque<>();
            Queue<ACTIONS> p2actionsQueue = new ArrayDeque<>();
            Queue<ACTIONS> p3actionsQueue = new ArrayDeque<>();
            Queue<ACTIONS> p4actionsQueue = new ArrayDeque<>();

            List<ACTIONS[]> actionsArrayList = log.getActions();

            for (ACTIONS[] actions : actionsArrayList) {
                p1actionsQueue.add(actions[0]);
                p2actionsQueue.add(actions[1]);
                p3actionsQueue.add(actions[2]);
                p4actionsQueue.add(actions[3]);
            }

            ArrayList<Player> players = new ArrayList<>();
            int playerID = TILETYPE.AGENT0.getKey();
            players.add(new SimonSaysPlayer(playerID++, p1actionsQueue));
            players.add(new SimonSaysPlayer(playerID++, p2actionsQueue));
            players.add(new SimonSaysPlayer(playerID++, p3actionsQueue));
            players.add(new SimonSaysPlayer(playerID++, p4actionsQueue));

            game.setPlayers(players);
        }
        return game;
    }

    public GameLog getGameLog() {
        return gameLog;
    }

    public int[] getPlayerOvertimes() {return playerOvertimes;}

    /**
     * Actor class for running multi-threaded games. Each player is an Actor.
     */
    public class Actor implements Runnable {

        private volatile  Types.ACTIONS action;
        public Player player;
        public GameState gamestate;

        Actor() {
//            this.player = player;
//            this.gamestate = gamestate;
        }

        @Override
        public void run() {
            action = player.act(this.gamestate);
        }

        public Types.ACTIONS getValue() {
            return action;
        }
    }
}
