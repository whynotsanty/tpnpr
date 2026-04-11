package org.eclipse.mosaic.app.npr;

import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.os.RoadSideUnitOperatingSystem;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;
import org.eclipse.mosaic.lib.util.scheduling.Event;

import java.util.HashMap;
import java.util.Map;

/**
 * Aplicação da RSU: Gere a densidade de tráfego e a velocidade média,
 * emitindo avisos DENM com Histerese quando detetado congestionamento.
 */
public class NprRsuApp extends AbstractApplication<RoadSideUnitOperatingSystem> implements CommunicationApplication {

    // --- CONFIGURAÇÃO DE HISTERESE ---
    private static final int LIMIAR_ALTO = 10; // Ativa o aviso se houver > 10 carros
    private static final int LIMIAR_BAIXO = 4; // Só desativa se baixar de 4 carros
    private boolean avisoAtivo = false;

    // --- MEMÓRIA DE DENSIDADE E VELOCIDADE ---
    // Guarda o último valor de velocidade recebido por cada veículo
    private final Map<String, Double> velocidadesVeiculos = new HashMap<>();

    @Override
    public void onStartup() {
        getOs().getAdHocModule().enable();
        System.out.println("✅ RSU " + getOs().getId() + " online com Gestão Dinâmica!");

        getOs().getEventManager().addEvent(getOs().getSimulationTime() + 1000000000L, this);
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        // Só processa NprCamMessage — ignora DENMs retransmitidos por veículos
        if (!(receivedV2xMessage.getMessage() instanceof NprCamMessage)) {
            return;
        }

        NprCamMessage cam = (NprCamMessage) receivedV2xMessage.getMessage();
        String vehicleId = cam.getRouting().getSource().getSourceName();
        velocidadesVeiculos.put(vehicleId, cam.getVelocidade());
    }

    @Override
    public void processEvent(Event event) throws Exception {
        int densidadeAtual = velocidadesVeiculos.size();

        // Calcular velocidade média dos veículos detetados
        double velMedia = 0;
        if (densidadeAtual > 0) {
            velMedia = velocidadesVeiculos.values().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
        }

        // Lógica de Histerese
        if (!avisoAtivo && densidadeAtual >= LIMIAR_ALTO) {
            avisoAtivo = true;
            System.out.println(String.format("[ALERTA] Densidade ALTA (%d veíc | vel. média: %.1f km/h). Ativando restrições.",
                    densidadeAtual, velMedia * 3.6));
        } else if (avisoAtivo && densidadeAtual <= LIMIAR_BAIXO) {
            avisoAtivo = false;
            System.out.println(String.format("[FLUXO] Densidade BAIXA (%d veíc | vel. média: %.1f km/h). Desativando restrições.",
                    densidadeAtual, velMedia * 3.6));
        } else {
            System.out.println(String.format("[RSU] %d veíc | vel. média: %.1f km/h | aviso: %s",
                    densidadeAtual, velMedia * 3.6, avisoAtivo ? "ATIVO" : "inativo"));
        }

        if (avisoAtivo) {
            enviarAvisoObras();
        }

        // Limpa para o próximo intervalo e reagenda para daqui a 2 segundos
        velocidadesVeiculos.clear();
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + 2000000000L, this);
    }

    private void enviarAvisoObras() {
        try {
            org.eclipse.mosaic.lib.objects.v2x.MessageRouting routing = getOs().getAdHocModule()
                    .createMessageRouting()
                    .topoBroadCast(1);

            long tempoDeValidade = getOs().getSimulationTime() + 5000000000L;
            NprDenmMessage aviso = new NprDenmMessage(routing, tempoDeValidade);
            getOs().getAdHocModule().sendV2xMessage(aviso);

            System.out.println("RSU " + getOs().getId() + " emitiu DENM: Zona de obras à frente!");

        } catch (Exception e) {
            System.out.println("Erro ao enviar DENM: " + e.getMessage());
        }
    }

    @Override
    public void onAcknowledgementReceived(ReceivedAcknowledgement acknowledgedMessage) {}

    @Override
    public void onCamBuilding(CamBuilder camBuilder) {}

    @Override
    public void onMessageTransmitted(V2xMessageTransmission v2xMessageTransmission) {}

    @Override
    public void onShutdown() {
        System.out.println("RSU " + getOs().getId() + " desligada.");
    }
}
