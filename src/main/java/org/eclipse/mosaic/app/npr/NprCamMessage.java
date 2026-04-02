package org.eclipse.mosaic.app.npr;

import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.EncodedPayload;

public class NprCamMessage extends V2xMessage {
    private final double velocidade;

    public NprCamMessage(MessageRouting routing, double velocidade) {
        // SOLUÇÃO: A classe V2xMessage exige o routing no construtor.
        super(routing); 
        this.velocidade = velocidade;
    }

    public double getVelocidade() { return velocidade; }

    @Override
    public EncodedPayload getPayload() {
        // Usamos 50L (long) para o tamanho da mensagem
        return new EncodedPayload(50L); 
    }
}