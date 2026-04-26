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


public class NprRsuApp extends AbstractApplication<RoadSideUnitOperatingSystem> implements CommunicationApplication { 
//A nossa classe herda uma classe base de aplicação e implementa a interface de comunicação para receber mensagens V2X

    private static final int LIMIAR_ALTO = 10; // Ativa o aviso se houver > 10 carros
    private static final int LIMIAR_BAIXO = 4; // Só desativa se baixar de 4 carros
    private boolean avisoAtivo = false;

    // Mapa para armazenar a velocidade de cada veículo detetado (ID do veículo -> velocidade)
    private final Map<String, Double> velocidadesVeiculos = new HashMap<>();

    @Override
    public void onStartup() {
        getOs().getAdHocModule().enable(); //Ligar antena AdHoc para receber mensagens dos veículos
        System.out.println("RSU " + getOs().getId() + " online com Gestão Dinâmica!");

        getOs().getEventManager().addEvent(getOs().getSimulationTime() + 1000000000L, this); 
        // Mete um despertador para a cada 1 segundo avaliar o transito 
    }

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        
        if (!(receivedV2xMessage.getMessage() instanceof NprCamMessage)) {
            return;
        }

        NprCamMessage cam = (NprCamMessage) receivedV2xMessage.getMessage();
        String vehicleId = cam.getRouting().getSource().getSourceName();
        velocidadesVeiculos.put(vehicleId, cam.getVelocidade());
        // Guarda a velocidade do veículo que enviou o CAM. A cada 1 segundo, o processoEvent irá calcular a velocidade média e tomar decisões com base nisso.
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

        // Converte a média para km/h
        double velMediaKmH = velMedia * 3.6;

        // Lógica de Histerese
        // Só ativa se houver carros suficientes e o trânsito estiver lento (< 40 km/h)
        if (!avisoAtivo && densidadeAtual >= LIMIAR_ALTO && velMediaKmH < 40.0) {
            avisoAtivo = true;
            System.out.println(String.format("[ALERTA] Trânsito detetado (%d veíc | vel. média: %.1f km/h). A ativar restrições.",
                    densidadeAtual, velMediaKmH));
        } 
        // Só desativa se a estrada ficar vazia ou o trânsito voltar a fluir rápido (> 60 km/h)
        else if (avisoAtivo && (densidadeAtual <= LIMIAR_BAIXO || velMediaKmH > 60.0)) {
            avisoAtivo = false;
            System.out.println(String.format("[FLUXO] Trânsito regularizado (%d veíc | vel. média: %.1f km/h). A desativar restrições.",
                    densidadeAtual, velMediaKmH));
        } else {
            System.out.println(String.format("[RSU] %d veíc | vel. média: %.1f km/h | aviso: %s",
                    densidadeAtual, velMediaKmH, avisoAtivo ? "ATIVO" : "inativo"));
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
                    .topoBroadCast(1); // Enviar apenas para veículos na zona de cobertura direta da RSU

            long tempoDeValidade = getOs().getSimulationTime() + 5000000000L; // TTL de 5 segundos
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