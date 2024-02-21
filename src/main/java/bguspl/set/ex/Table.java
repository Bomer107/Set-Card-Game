package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Iterator;

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

    public synchronized void placeCards(List<Integer> deck){
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

    public synchronized void removeCards(int[] playerTokens) {
        for(int token : playerTokens){
            removeCard(token);
            env.ui.removeTokens(token);
            Iterator<Player> iter = queueOfPlayers.iterator();
            while(iter.hasNext()){
                Player player = iter.next();
                int[] thisPlayerTokens = player.getTokens();
                for(int i = 0; i < thisPlayerTokens.length; i++){
                    if(thisPlayerTokens[i] == token){
                        player.tokenRemoved(token);
                        iter.remove(); //removing from the queue
                    }
                }

            }
        }
    }

    public synchronized void removeAllCardsFromTable(List<Integer> deck){
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
    }

    public void placeToken(Player player, int slot)
    {
        if(slotToCard(slot) == null){
            return;
        }
        placeToken(player.id, slot);
        player.placeToken(slot);
        if(player.getNumTokens() == env.config.featureSize){
            synchronized(this){
                player.setDontGetInput(true);
                env.logger.info("player " + player.id + " added to the queue");
                queueOfPlayers.add(player);
                synchronized(player.dealer){
                    (player.dealer).notify();
                }  
            }
        }
       
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public synchronized boolean removeToken(int player, int slot) {
        if(slotToCard[slot] != null){
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
