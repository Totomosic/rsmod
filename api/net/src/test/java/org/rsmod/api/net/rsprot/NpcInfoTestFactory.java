package org.rsmod.api.net.rsprot;

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.rsprot.compression.HuffmanCodec;
import net.rsprot.compression.provider.DefaultHuffmanCodecProvider;
import net.rsprot.protocol.api.EntityInfoProtocols;
import net.rsprot.protocol.api.suppliers.NpcInfoSupplier;
import net.rsprot.protocol.api.suppliers.PlayerInfoSupplier;
import net.rsprot.protocol.api.suppliers.WorldEntityInfoSupplier;
import net.rsprot.protocol.common.client.OldSchoolClientType;

/** Uses RSProt's Java bootstrap API to test the protocol without opening a network service. */
public final class NpcInfoTestFactory {
    private NpcInfoTestFactory() {}

    public static EntityInfoProtocols create() throws IOException {
        byte[] codewords = new byte[256];
        Arrays.fill(codewords, (byte) 8);
        var data = Unpooled.wrappedBuffer(codewords);
        try {
            return EntityInfoProtocols.Companion.initialize(
                UnpooledByteBufAllocator.DEFAULT,
                List.of(OldSchoolClientType.DESKTOP),
                new DefaultHuffmanCodecProvider(HuffmanCodec.Companion.create(data)),
                new PlayerInfoSupplier(),
                new NpcInfoSupplier(),
                new WorldEntityInfoSupplier(),
                null
            );
        } finally {
            data.release();
        }
    }
}
