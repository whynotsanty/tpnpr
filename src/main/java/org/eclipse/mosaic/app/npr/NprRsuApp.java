package org.eclipse.mosaic.app.npr;

import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.os.RoadSideUnitOperatingSystem;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.util.scheduling.Event;

import java.util.HashSet;
import java.util.Set;

/**
 * Aplicação da RSU: Gere a densidade de tráfego e emite avisos DENM com Histerese.
 */
public class NprRsuApp extends AbstractApplication<RoadSideUnitOperatingSystem> implements CommunicationApplication {

    // --- CONFIGURAÇÃO DE HISTERESE ---
    // Valores para evitar o efeito "pisca-pisca" no aviso
    private static final int LIMIAR_ALTO = 10;  // Ativa o aviso se houver > 10 carros
    private static final int LIMIAR_BAIXO = 4;  // Só desativa se baixar de 4 carros
    private boolean avisoAtivo = false;

    // --- MEMÓRIA DE DENSIDADE ---
    private final Set<String> veiculosDetectados = new HashSet<>();

    @Override
    public void onStartup() {
        getOs().getAdHocModule().enable();
        System.out.println(" ✅ RSU " + getOs().getId() + " online com Gestão Dinâmica!");
        
        // Agendamos a primeira verificação para daqui a 1 segundo
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + 1000000000L, this);
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        // Usando o getSourceName() que o teu VS Code confirmou existir!
        String vehicleId = receivedV2xMessage.getMessage().getRouting().getSource().getSourceName();
        veiculosDetectados.add(vehicleId);
    }

    @Override
    public void processEvent(Event event) throws Exception {
        int densidadeAtual = veiculosDetectados.size();

        // Lógica de Histerese (Decisão de ativar/desativar o aviso)
        if (!avisoAtivo && densidadeAtual >= LIMIAR_ALTO) {
            avisoAtivo = true;
            System.out.println("[ALERTA] Densidade ALTA (" + densidadeAtual + " veíc). Ativando restrições.");
        } 
        else if (avisoAtivo && densidadeAtual <= LIMIAR_BAIXO) {
            avisoAtivo = false;
            System.out.println("[FLUXO] Densidade BAIXA (" + densidadeAtual + " veíc). Desativando restrições.");
        }

        // Se estiver ativo, envia o grito
        if (avisoAtivo) {
            enviarAvisoObras();
        }

        // Limpa para o próximo intervalo e reagenda para daqui a 2 segundos
        veiculosDetectados.clear();
        getOs().getEventManager().addEvent(getOs().getSimulationTime() + 2000000000L, this);
    }

    private void enviarAvisoObras() {
        try {
            org.eclipse.mosaic.lib.objects.v2x.MessageRouting routing = getOs().getAdHocModule()
                    .createMessageRouting()
                    .topoBroadCast(1); 

            // Criamos a validade da mensagem: Tempo Atual + 5 Segundos (5.000.000.000 nanosegundos)
            long tempoDeValidade = getOs().getSimulationTime() + 5000000000L;
            
            // Passamos a validade no construtor da DENM
            NprDenmMessage aviso = new NprDenmMessage(routing, 13.89, tempoDeValidade); 
            getOs().getAdHocModule().sendV2xMessage(aviso);

            System.out.println("RSU " + getOs().getId() + " GRITOU: Reduzam para 50km/h!");

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