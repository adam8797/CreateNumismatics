package dev.ithundxr.createnumismatics.content.checkout;

import com.mojang.datafixers.types.templates.Check;
import com.simibubi.create.foundation.gui.menu.MenuBase;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import dev.ithundxr.createnumismatics.content.backend.Coin;
import dev.ithundxr.createnumismatics.content.bank.BankMenu;
import dev.ithundxr.createnumismatics.content.bank.CardItem;
import dev.ithundxr.createnumismatics.content.bank.CardSlot;
import dev.ithundxr.createnumismatics.content.coins.CoinItem;
import dev.ithundxr.createnumismatics.content.coins.SlotInputMergingCoinBag;
import dev.ithundxr.createnumismatics.content.coins.SlotOutputMergingCoinBag;
import dev.ithundxr.createnumismatics.registry.NumismaticsTags;
import dev.ithundxr.createnumismatics.util.Utils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class CheckoutMenu extends MenuBase<DeferredCheckoutOrder>
{
    protected ContainerData dataAccess;
    private CheckoutMenu.CardSwitchContainer cardSwitchContainer;
    protected UUID currentCardUUID = Utils.emptyUUID;

    public CheckoutMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
        super(type, id, inv, extraData);
    }

    public CheckoutMenu(MenuType<?> type, int id, Inventory inv, DeferredCheckoutOrder contentHolder, ContainerData dataAccess) {
        super(type, id, inv, contentHolder);
        this.dataAccess = dataAccess;
        addDataSlots(dataAccess);
    }

    @Override
    protected DeferredCheckoutOrder createOnClient(RegistryFriendlyByteBuf extraData) {
        DeferredCheckoutOrder account = DeferredCheckoutOrder.clientSide(extraData);
        this.dataAccess = account.dataAccess;
        addDataSlots(dataAccess);
        return account;
    }

    @Override
    protected void initAndReadInventory(DeferredCheckoutOrder contentHolder) {}

    @Override
    protected void addSlots() {
        if (cardSwitchContainer == null)
            cardSwitchContainer = new CheckoutMenu.CardSwitchContainer(this::slotsChanged, (id) -> {
                currentCardUUID = id;
                return true;
            });

        addSlot(new CardSlot.BoundCardSlot(cardSwitchContainer, 0, 148, 73));
        addPlayerSlots(40, 152);
    }

    @Override
    protected void saveData(DeferredCheckoutOrder contentHolder) {}

    @Override
    public void removed(Player playerIn) {
        super.removed(playerIn);
        if (playerIn instanceof ServerPlayer) {
            clearContainer(player, cardSwitchContainer);
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) { // index is slot that was clicked
        Slot clickedSlot = this.slots.get(index);

        if (!clickedSlot.hasItem())
            return ItemStack.EMPTY;

        ItemStack slotStack = CoinItem.clearDisplayedCount(clickedSlot.getItem());
        ItemStack returnStack = slotStack.copy();

        if (slotStack.isEmpty()) {
            clickedSlot.set(ItemStack.EMPTY);
        } else {
            clickedSlot.setChanged();
        }

        return returnStack;
    }

    private class CardSwitchContainer implements Container {
        private final Consumer<CheckoutMenu.CardSwitchContainer> slotsChangedCallback;
        private final Function<UUID, Boolean> uuidChangedCallback; // should return success

        @NotNull
        protected final List<ItemStack> stacks = new ArrayList<>();

        public CardSwitchContainer(Consumer<CheckoutMenu.CardSwitchContainer> slotsChangedCallback, Function<UUID, Boolean> uuidChangedCallback) {
            this.slotsChangedCallback = slotsChangedCallback;
            this.uuidChangedCallback = uuidChangedCallback;
            stacks.add(ItemStack.EMPTY);
        }

        @Override
        public int getContainerSize() {
            return 1;
        }

        protected ItemStack getStack() {
            return stacks.get(0);
        }

        @Override
        public boolean isEmpty() {
            return getStack().isEmpty();
        }

        @Override
        public @NotNull ItemStack getItem(int slot) {
            return getStack();
        }

        @Override
        public @NotNull ItemStack removeItem(int slot, int amount) {
            ItemStack stack = ContainerHelper.removeItem(this.stacks, 0, amount);
            if (!stack.isEmpty()) {
                this.slotsChangedCallback.accept(this);
            }
            return stack;
        }

        @Override
        public @NotNull ItemStack removeItemNoUpdate(int slot) {
            return ContainerHelper.takeItem(this.stacks, 0);
        }

        @Override
        public void setItem(int slot, @NotNull ItemStack stack) {
            this.stacks.set(0, stack);
            if (CardItem.isBound(stack) && NumismaticsTags.AllItemTags.CARDS.matches(stack)) {
                if (!this.uuidChangedCallback.apply(CardItem.get(stack))) {
                    // Non-existent account
                    stacks.set(0, CardItem.clear(stack));
                    CheckoutMenu.this.clearContainer(CheckoutMenu.this.player, this);
                }
            }
            this.slotsChangedCallback.accept(this);
        }

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(@NotNull Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            this.stacks.set(0, ItemStack.EMPTY);
        }
    }
}
