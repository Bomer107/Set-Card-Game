package bguspl.set.ex;

import bguspl.set.Env;
import java.util.LinkedList;
import java.util.Queue;
/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private int[][] tokens = new int[3][2];
    private int tokenSize = 0;
    private  Queue<int[]> queue = new LinkedList<int[]>();
    private boolean finish;
    /**
     * The dealer of the game.
     */
    Dealer dealer;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            if(!queue.isEmpty())
                checkToken();  
            if(tokenSize==3)
                wakeDealer();
            }
            
        
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                try {
                    synchronized (this) { wait(); }
                } catch (InterruptedException ignored) {}
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        Integer card=table.slotToCard(slot);
        int []action={slot,card};
        queue.add(action);


    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        
        // TODO implement

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement
    }

    public int score() {
        return score;
    }
    private void checkToken(){
        while(!queue.isEmpty()){
            int []action=queue.poll();
            boolean isfind =false;
            for(int i=0;i<2&isfind;i++){
                if(action[1]==tokens[i][1])
                    isfind=true;
                    removeToken(action[1],i);
            }
            if(!isfind&tokenSize<env.config.featureSize)
                addToken(action);
        }
    }
    public void removeToken(int slot,int rowToDelete){
        for (int i = rowToDelete; i < tokens.length - 1; i++) {
            tokens[i] = tokens[i + 1];
        }
        tokens[tokens.length - 1] = new int[tokens[0].length];
        tokenSize--;
        boolean isremove= table.removeToken(slot,id);
    }   

    
    public void addToken(int[]token){
        tokens[tokenSize]=token;
        tokenSize++;
        table.placeToken(id,token[0]);

    }
    private void wakeDealer()  throws Exception{
        table.sem.acquire();
        boolean isfind=true;
        for(int i=0;i<env.config.featureSize&isfind;i++){
            if(tokens[i][1]!=table.slotToCard(tokens[i][0])){
                isfind=false;
                table.sem.release();
                removeToken(tokens[i][0], i);
            }
        }
        if(isfind){
            table.setCardToCheack(tokens, id);
            dealer.notify();
            synchronized(dealer.wait){
            while (!finish) {
                wait();   
            }
            table.sem.release();
        }
        }
    }
}
