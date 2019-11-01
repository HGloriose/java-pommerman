package players.heuristics;

import core.GameState;

public abstract class StateHeuristic {
    public abstract double evaluateState(GameState gs); //each heuristic will have different ways of evaluating the game
}
