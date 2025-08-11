package dev.ithundxr.createnumismatics.content.checkout;

import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.PackageOrder;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.tableCloth.ShoppingListItem;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import dev.ithundxr.createnumismatics.content.backend.Coin;
import dev.ithundxr.createnumismatics.content.coins.CoinItem;
import dev.ithundxr.createnumismatics.content.coins.DiscreteCoinBag;
import dev.ithundxr.createnumismatics.content.depositor.AbstractDepositorBlockEntity;
import dev.ithundxr.createnumismatics.mixin.MixinStockTickerBlockEntityReceivedPaymentsAccessor;
import dev.ithundxr.createnumismatics.registry.NumismaticsItems;
import dev.ithundxr.createnumismatics.registry.NumismaticsMenuTypes;
import dev.ithundxr.createnumismatics.registry.NumismaticsTags;
import dev.ithundxr.createnumismatics.util.Utils;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class DeferredCheckoutOrder implements MenuProvider
{
    public UUID id;
    public InventorySummary itemCost;
    public int costInSpurs;
    public PackageOrder deferredOrder;
    public Level level;
    public ServerPlayer player;
    public StockTickerBlockEntity stockTicker;
    public boolean finalized = false;
    public boolean clientSide;

    public final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            //Numismatics.LOGGER.warn("BankAccount dataAccess#get called with index " + index + " (Account: "+BankAccount.this+"), returning "+balance);
            return costInSpurs;
        }

        @Override
        public void set(int index, int value) {
            Numismatics.LOGGER.warn("BankAccount dataAccess#set called with index " + index + " (Account: "+DeferredCheckoutOrder.this+"), setting balance to "+value);
        }

        @Override
        public int getCount() {
            return 1;
        }
    };

    public DeferredCheckoutOrder(UUID orderId, ShoppingListItem.ShoppingList list, Level level, ServerPlayer player, StockTickerBlockEntity stockTicker)
    {
        Couple<InventorySummary> bakeEntries = list.bakeEntries(level, null);
        InventorySummary paymentEntries = bakeEntries.getSecond();

        // Determine cost of coin component of order
        InventorySummary paymentWithoutCoins = new InventorySummary();
        for (var stack : paymentEntries.getStacksByCount())
        {
            if (stack.stack.getItem() instanceof CoinItem coinItem) {
                costInSpurs += coinItem.coin.toSpurs(stack.count);
            }
            else {
                paymentWithoutCoins.add(stack);
            }
        }

        this.id = orderId;
        this.itemCost = paymentWithoutCoins;
        this.deferredOrder = new PackageOrder(bakeEntries.getFirst().getStacksByCount());
        this.level = level;
        this.player = player;
        this.stockTicker = stockTicker;
        this.clientSide = false;
    }

    private DeferredCheckoutOrder(UUID orderId, int costInSpurs)
    {
        this.clientSide = true;
        this.id = orderId;
        this.costInSpurs = costInSpurs;
    }

    public boolean isTransactionValid()
    {
        if (finalized)
            return false;

        if (clientSide)
            return false;

        if (level.isClientSide)
            return false;

        if (player.hasDisconnected())
            return false;

        if (stockTicker.isRemoved())
            return false;

        if (costInSpurs == 0)
            return false;

        var depositor = getDepositor(stockTicker.getBlockPos(), level);
        if (depositor == null)
            return false;

        return true;
    }

    public boolean completePurchase(CheckoutPaymentMethod method, UUID purchasingAccountId)
    {
        if (method == CheckoutPaymentMethod.UNDEFINED)
            return false;

        BankAccount account = null;
        if (method == CheckoutPaymentMethod.CARD)
        {
            if (purchasingAccountId.equals(Utils.emptyUUID))
            {
                Numismatics.LOGGER.warn("Attempted to complete a card transaction {} with default bank account", id);
                return false;
            }

            account = Numismatics.BANK.getAccount(purchasingAccountId);
            if (account == null)
            {
                Numismatics.LOGGER.warn("Attempted to complete a card transaction {} with an non-empty, but invalid bank account {}", id, purchasingAccountId);
                return false;
            }
        }

        if (!isTransactionValid())
        {
            Numismatics.LOGGER.warn("Attempted to complete an invalid transaction with UUID " + id);
            return false;
        }

        if (itemCost.isEmpty())
        {
            if (!CheckoutUtilities.checkOrderPreconditions(stockTicker, deferredOrder, level, player))
            {
                CheckoutUtilities.denyPurchase(level, player, "stock_keeper.too_broke");
                return false;
            }

            if (method == CheckoutPaymentMethod.CARD && account.getBalance() < costInSpurs)
            {
                CheckoutUtilities.denyPurchase(level, player, "stock_keeper.too_broke");
                return false;
            }

            if (method == CheckoutPaymentMethod.COINS && !playerHasEnoughCoinsInInventory(player.getInventory(), costInSpurs))
            {
                CheckoutUtilities.denyPurchase(level, player, "stock_keeper.too_broke");
                return false;
            }

            // If there's no item cost, we can skip a lot of the default create interaction, and just submit the order
            CheckoutUtilities.shopInteractionSubmitToNetwork(stockTicker, deferredOrder, player, level);
        }
        else
        {
            // There are item costs in the shopping list, so we must submit the order through the standard pipeline.
            var receivedPayments = ((MixinStockTickerBlockEntityReceivedPaymentsAccessor)stockTicker).getReceivedPayments();
            if (!CheckoutUtilities.finishShopInteractionStock(stockTicker, level, player, itemCost, deferredOrder, receivedPayments))
            {
                // stock checkout failed, cancel the transaction
                return false;
            }
        }

        switch (method)
        {
            case CARD -> account.deduct(costInSpurs);
            case COINS -> tryPayInSpurs(player.getInventory(), costInSpurs);
            default -> throw new IllegalStateException("Unexpected value: " + method);
        }

        depositCoinsToMerchant();
        return true;
    }

    /**
     * Attempts to remove the specified number of spurs from the player's inventory.
     * - Uses the largest denominations first (greedy).
     * - If exact change is not possible with available smaller coins, it takes one larger coin
     *   and gives change back in smaller coins.
     * - If still impossible (not enough value in inventory), returns false and makes no changes.
     *
     * @param player the player
     * @param spursToRemove amount to pay, in spurs (must be >= 0)
     * @return true if payment succeeded (inventory adjusted and change returned), false otherwise
     */
    public boolean tryPayInSpurs(Inventory inventory, int spursToRemove) {
        if (spursToRemove <= 0)
        {
            return true; // nothing to pay
        }

        // 1. Count available coins in the player's inventory
        DiscreteCoinBag available = new DiscreteCoinBag();
        for (int i = 0; i < inventory.getContainerSize(); i++)
        {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() instanceof CoinItem coinItem)
            {
                available.add(coinItem.coin, stack.getCount());
            }
        }

        if (available.getValue() < spursToRemove)
        {
            return false;
        }

        // 2. Plan which coins to remove (greedy from largest to smallest)
        DiscreteCoinBag toRemove = new DiscreteCoinBag();
        int remaining = spursToRemove;
        for (Coin coin : Coin.byValueDescending)
        {
            if (remaining <= 0)
                break;

            int canUse = Math.min(available.getDiscrete(coin), remaining / coin.value);
            if (canUse > 0)
            {
                toRemove.add(coin, canUse);
                remaining -= coin.toSpurs(canUse);
            }
        }

        // 3. If we still have remaining spurs to cover, try to break one larger coin
        // Find the smallest denomination that is strictly larger than 'remaining' and still available
        DiscreteCoinBag changeToAdd = new DiscreteCoinBag();
        if (remaining > 0)
        {
            // Search ascending for the smallest coin whose value >= remaining and still available
            Coin breaker = null;
            for (Coin coin : Coin.byValueAscending)
            {
                int availableCount = available.getDiscrete(coin) - toRemove.getDiscrete(coin);
                if (availableCount > 0 && coin.value >= remaining) {
                    breaker = coin;
                    break;
                }
            }

            if (breaker == null) {
                // Can't cover the remaining with a single larger coin; payment impossible
                return false;
            }

            // Use one breaker coin
            toRemove.add(breaker, 1);
            int overpay = breaker.value - remaining;

            // Make change
            changeToAdd = DiscreteCoinBag.of(overpay);
            overpay -= changeToAdd.getValue();
            // If we couldn't form exact change (shouldn't happen with canonical set), fail safely
            if (overpay != 0) {
                return false;
            }
        }

        // 4. Apply Changes
        if (!CoinItem.extract(player, InteractionHand.MAIN_HAND, toRemove.asMap(), true, false)) {
            return false;
        }
        if (!CoinItem.extract(player, InteractionHand.MAIN_HAND, toRemove.asMap(), false, false)) {
            return false;
        }

        // 5. Return change to the player
        for (Coin coin : Coin.values()) {
            int count = changeToAdd.getDiscrete(coin);
            if (count <= 0)
                continue;

            // Split into max stack sizes as needed
            int max = coin.asStack().getMaxStackSize();
            int left = count;
            while (left > 0) {
                int n = Math.min(max, left);
                ItemStack change = coin.asStack(n);
                player.getInventory().placeItemBackInInventory(change);
                left -= n;
            }
        }

        return true;
    }

    private boolean playerHasEnoughCoinsInInventory(Inventory inv, int target)
    {
        var spursInInventory = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            var stack = inv.getItem(slot);
            if (stack.getItem() instanceof CoinItem coin)
            {
                spursInInventory += coin.coin.toSpurs(stack.getCount());
                if (spursInInventory >= target)
                    return true;
            }
        }
        return false;
    }


    private static AbstractDepositorBlockEntity getDepositor(BlockPos tickerPos, Level level)
    {
        for (Direction side : Iterate.horizontalDirections) {
            BlockPos pos = tickerPos.relative(side);
            var e = level.getBlockEntity(pos);
            if (e instanceof AbstractDepositorBlockEntity)
                return (AbstractDepositorBlockEntity)e;
        }
        return null;
    }

    private void depositCoinsToMerchant()
    {
        var depositor = getDepositor(stockTicker.getBlockPos(), level);
        if (depositor == null)
            return;

        var account = Numismatics.BANK.getAccount(depositor.getDepositAccount());
        if (account != null)
        {
            account.deposit(costInSpurs);
        }
        else
        {
            var coins = DiscreteCoinBag.of(costInSpurs);
            for (var c : Coin.values())
            {
                depositor.addCoin(c, coins.getDiscrete(c));
            }
        }
    }


    // Menu components

    @Override
    public Component getDisplayName() {
        return Component.translatable("gui.numismatics.checkout_screen.header");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory inventory, Player player) {
        return new CheckoutMenu(NumismaticsMenuTypes.CHECKOUT.get(), i, inventory, this, dataAccess);
    }

    public void sendToMenu(FriendlyByteBuf buf) {
        buf.writeUUID(this.id);
        buf.writeVarInt(this.costInSpurs);
    }

    public static DeferredCheckoutOrder clientSide(FriendlyByteBuf buf) {
        return new DeferredCheckoutOrder(buf.readUUID(), buf.readVarInt());
    }

    public static boolean isPowerOfTwo(int x)
    {
        return (x & (x - 1)) == 0;
    }
}
