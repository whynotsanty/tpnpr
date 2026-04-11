package org.eclipse.mosaic.app.npr;

import org.eclipse.mosaic.lib.objects.v2x.EncodedPayload;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;

public class NprDenmMessage extends V2xMessage {
    private final long tempoExpiracao;

    public NprDenmMessage(MessageRouting routing, long tempoExpiracao) {
        super(routing);
        this.tempoExpiracao = tempoExpiracao;
    }

    public long getTempoExpiracao() { return tempoExpiracao; }

    @Override
    public EncodedPayload getPayload() {
        return new EncodedPayload(100, 800);
    }

    @Override
    public String toString() {
        return "NprDenmMessage{exp=" + tempoExpiracao + "}";
    }
}
