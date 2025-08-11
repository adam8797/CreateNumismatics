package dev.ithundxr.createnumismatics.content.checkout;

import dev.ithundxr.createnumismatics.content.backend.Coin;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.codecs.stream.CatnipStreamCodecBuilders;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

import java.util.function.IntFunction;

public enum CheckoutPaymentMethod
{
    UNDEFINED,
    CARD,
    COINS;

    public static final StreamCodec<ByteBuf, CheckoutPaymentMethod> STREAM_CODEC = CatnipStreamCodecBuilders.ofEnum(CheckoutPaymentMethod.class);

}
