package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Iterator;
import java.util.concurrent.Semaphore;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    public final LinkedList<Player> queueOfPlayers;

    private volatile boolean dealerChanges;
    private volatile boolean playerChanges;
    public Semaphore sem2;



    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.queueOfPlayers = new LinkedList<Player>();
        sem2=new Semaphore(1,true);
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        
        env.ui.placeCard(card, slot);
    }

    public  void placeCards(List<Integer> deck){
       
        for (int slot = 0; slot < env.config.tableSize && !deck.isEmpty(); ++slot)
            if (slotToCard(slot) == null){
                Integer card = deck.remove(0);
                placeCard(card, slot);
            }
            
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        Integer card = slotToCard[slot];
        slotToCard[slot] = null;
        cardToSlot[card] = null;
        env.ui.removeCard(slot);
    }

    public  void removeCards(int[] playerTokens,Dealer dealer) {
        try {
            sem2.acquire(); 
        } catch (InterruptedException e) {}
        System.out.println("sem2 is acquire by dealer");  
        for(int token : playerTokens){
            removeCard(token);
            env.ui.removeTokens(token);
            Iterator<Player> iter=queueOfPlayers.iterator();
            while(iter.hasNext()){
                Player player=iter.next();
                System.out.println("player in queu"+player.id);
                int[] thisPlayerTokens = player.getTokens();
               
                for(int i = 0; i < thisPlayerTokens.length; i++){
                    if(thisPlayerTokens[i] == token){
                        player.tokenRemoved(token);
                        iter.remove();
                        player.setDontGetInput(false);
                        synchronized(player){
                            player.notify();
                        }
                    }
                }
            }
            System.out.println("queu isempty: "+queueOfPlayers.isEmpty());
            Player [] players = dealer.getPlayer();
           
           for(int j=0;j<env.config.players;j++){
          
                 int[] thisPlayerTokens = players[j].getTokens();
                for(int k = 0; k < thisPlayerTokens.length; k++){
                    if(thisPlayerTokens[k] == token){
                        
                       
                        players[j].tokenRemoved(token);
                        
                    }
                }

            }
        }
        synchronized(dealer.right){
            dealer.right.setDontGetInput(false);
            dealer.right.notify();
        }
        System.out.println(" is release by dealer1"); 
        sem2.release();
        System.out.println(" is release by dealer2");  
    }
        
    public  void removeAllCardsFromTable(List<Integer> deck){
        env.logger.info("Deleting all cards from the table");
        env.ui.removeTokens();
        queueOfPlayers.clear();
        for(int slot = 0; slot < env.config.tableSize; ++slot){
            if(slotToCard(slot) != null){
                deck.add(slotToCard(slot));
                removeCard(slot);
            }
        }
    }   

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        env.ui.placeToken(player, slot);
        System.out.println("place token: "+slot+" ,player: "+player);
    }

    public void placeToken(Player player, int slot)
    {
        try {
            sem2.acquire();   
        } catch (InterruptedException e) {}
        System.out.println("sem2 is acquire by  "+player.id+" in place token");  
        
        if(slotToCard(slot) == null){
            System.out.println("sem2 is realese by null  "+player.id);   
            sem2.release();

            return;
        }
        placeToken(player.id, slot);
        player.placeToken(slot);
        if(player.getNumTokens() == env.config.featureSize){
            try {
                player.sem.acquire();   
            } catch (InterruptedException e) {}
            player.setDontGetInput(true);
            player.clear();
            player.sem.release();
            env.logger.info("player, " + player.id + "  ,added to the queue");
            queueOfPlayers.add(player);
            sem2.release();
            System.out.println("sem2 is realse in placetoken  "+player.id); 
            System.out.println("player, "+player.id+" ,add himslf to queu");
            
            synchronized(player.dealer){
                (player.dealer).notify();
                  
            }
        }
        else{
        sem2.release();
        System.out.println("sem2 is realese by  "+player.id);
        } 
       
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        if(slotToCard[slot] != null){
            System.out.println("player, "+player+" ,remove token, " +slot);
            env.ui.removeToken(player, slot);
            return true;
        }
        return false;
    }

    public Integer cardToSlot(Integer card){
        return cardToSlot[card];
    }

    public Integer slotToCard(Integer slot){
        return slotToCard[slot];
    }

}

