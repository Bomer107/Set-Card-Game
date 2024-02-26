package bguspl.set.ex;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.Semaphore;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;
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

    private LinkedList<Integer> tokens;

    private LinkedList<Integer> keyInputQueue;

    public long freeze;
    private final long SECOND = 1000L;
    private volatile boolean dontGetInput;
    private volatile boolean waiting;
    public Semaphore sem;
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
        this.tokens = new LinkedList<Integer>();
        this.keyInputQueue = new LinkedList<Integer>();
        this.freeze = 0;    
        playerThread = null;
        dontGetInput = false;
        waiting = false;
        sem =new Semaphore(1);

    }

    public void setPlayerThread(ThreadLogger playerThread){
        this.playerThread = playerThread;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting it's run() function.");
        if (!human) {
        createArtificialIntelligence();
        
        }
        while (!terminate) {
            if(freeze==0){
                if(keyInputQueue.isEmpty()|dontGetInput){
                    System.out.println("key queu of " +id+ " is impty "+ keyInputQueue.isEmpty());
                    System.out.println( +id+ " is dontgetinput "+ dontGetInput); 

                try {
                    synchronized(this){
                        waiting = true; 
                        System.out.println(id+" is wait");
                        this.wait();}
                    } catch (Exception ignored) {}
                waiting = false;
                }
            }
            System.out.println(id+" notift");
            while(freeze > 0 && !terminate){
                
                env.ui.setFreeze(id, freeze);
                synchronized(this){

                    try{env.logger.info("player got freezed");
                        wait(SECOND);} catch(InterruptedException e){}

                    freeze -= SECOND;
                }
                if(freeze == 0)
                    env.ui.setFreeze(id, freeze);
            }
                System.out.println("player, "+id +" ,enter to execute ");
                executeAction();
             
        }
            
       // if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        //env.logger.info("thread " + Thread.currentThread().getName() + " ending it's run() function.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rand = new Random();
            
            while(!terminate){
                int press=rand.nextInt(12);
                keyPressed(press);
                
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
        
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        try{
            ((ThreadLogger)playerThread).joinWithLog();
        } catch (InterruptedException e) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
            if(freeze > 0 || getDontGetInput()){
            
                return;
            }
                try {
                    sem.acquire();   
                } catch (InterruptedException e) {}
                if(freeze > 0 || getDontGetInput()){
                    sem.release();
                    
                    return;
                }
                if(keyInputQueue.size()<3){
                System.out.println("key queu :"+id+"  add to keyqueu :"+slot);
                keyInputQueue.add(slot);
                System.out.println("peek keyqueu :"+id+": = "+keyInputQueue.peek());
                }
                synchronized(this){
                    if(waiting){
                        System.out.println("player "+id+" is notify by : input mneger");
                        this.notify();
                    }
                }
            
                sem.release();
            }
        
       

    public void executeAction(){
        try {
            sem.acquire();
            System.out.println("sem aquire by "+ id);   
        } catch (InterruptedException e) {}
        System.out.println("key queu :"+id+"  is empty :"+keyInputQueue.isEmpty());
        if(keyInputQueue.isEmpty()){
            sem.release();
            System.out.println("sem realse by "+ id);
            return;
        }
            System.out.println(id+" pull from key queue");
            System.out.println("key queu :"+id+"  is empty :"+keyInputQueue.isEmpty());
            Integer slot = keyInputQueue.poll();
            System.out.println("slot ,"+slot+" ,get out from keyqueue of player :"+ id);
            System.out.println("sem realse by "+ id);
            sem.release();
            try {
                table.sem2.acquire();   
            } catch (InterruptedException e) {}
            Iterator<Integer> iter = tokens.iterator();
            
            while(iter.hasNext()){
                Integer token = iter.next();
                if(token.equals(slot)){
                    
                    
                    
                    table.removeToken(id, slot);
                    iter.remove();
                    System.out.println("sem2 is realease by "+id); 
                    table.sem2.release();
                    
                    
                    return;
                }
            }
            table.sem2.release();
           
            if(tokens.size() < env.config.featureSize){
                
                
                table.placeToken(this, slot);
            }
        
        
    }

    public void placeToken(int slot){
        tokens.add(slot);
    }

    public int[] getCards()
    {
        int [] getCards = new int[env.config.featureSize];
        Iterator<Integer> iter = tokens.iterator();
        int tokenNum = 0;
        while (iter.hasNext())
            getCards[tokenNum++] = table.slotToCard(iter.next());
        return getCards;
    }

    public int[] getTokens(){
        int [] getTokens = new int[env.config.featureSize];
        Iterator<Integer> iter = tokens.iterator();
        int tokenNum = 0;
        while (iter.hasNext())
            getTokens[tokenNum++] = (iter.next()).intValue();
        return getTokens;
    }

    public int getNumTokens(){
        return tokens.size();
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        freeze = env.config.pointFreezeMillis;
        //freeze=0;
        keyInputQueue.clear();
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        freeze = env.config.penaltyFreezeMillis;
       //freeze=0;
    }

    public int score() {
        return score;
    }

    public void tokenRemoved(int tokenToRemove){
       
        Iterator<Integer> iter = tokens.iterator();
        while(iter.hasNext()){
            Integer token = iter.next();
            if(token.equals(tokenToRemove))
                iter.remove();
        }
    }

    public void newBoard(){
        tokens.clear();
        keyInputQueue.clear();
    }

    public synchronized void setDontGetInput(boolean dontGetInput){
        this.dontGetInput = dontGetInput;
    }

    public synchronized boolean getDontGetInput(){
        return dontGetInput;
    }

    public synchronized boolean getWaiting(){
        return waiting;
    }
    public void clear(){
    System.out.println("key queu :"+id+"  is clear");
    
        keyInputQueue.clear();
        System.out.println("key queu is empty ="+keyInputQueue.isEmpty());
    }
}
