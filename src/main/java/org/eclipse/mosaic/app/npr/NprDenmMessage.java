package org.eclipse.mosaic.app.npr;

import org.eclipse.mosaic.lib.objects.v2x.EncodedPayload;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;

public class NprDenmMessage extends V2xMessage {
    private final double velocidadeRecomendada;
    private final long tempoExpiracao;

    public NprDenmMessage(MessageRouting routing, double velocidade, long tempoExpiracao) {
        super(routing);
        this.velocidadeRecomendada = velocidade;
        this.tempoExpiracao = tempoExpiracao;
    }

    public double getVelocidadeRecomendada() { return velocidadeRecomendada; }
    public long getTempoExpiracao() { return tempoExpiracao; }

    @Override
    public EncodedPayload getPayload() {
        // Dizemos ao simulador que esta mensagem pesa 100 bytes (e 800 bits)
        return new EncodedPayload(100, 800);
    }

    @Override
    public String toString() {
        return "NprDenmMessage{vel=" + velocidadeRecomendada + ", exp=" + tempoExpiracao + "}";
    }
}