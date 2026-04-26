package org.eclipse.mosaic.app.npr;

import java.awt.Color;
import org.eclipse.mosaic.fed.application.ambassador.simulation.VehicleParameters;
import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.VehicleApplication;
import org.eclipse.mosaic.fed.application.app.api.os.VehicleOperatingSystem;
import org.eclipse.mosaic.lib.objects.vehicle.VehicleData;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;

public class NprVehicleApp extends AbstractApplication<VehicleOperatingSystem> implements VehicleApplication, CommunicationApplication {

    // Implementação do veículo com personalidades
    public enum Personalidade {
        COOPERANTE, //Segue sempre as ordens e trava suavemente
        PADRAO, // Segue as ordens 50% das vezes, com desaceleração normal
        POUCO_COOPERANTE, // Segue as ordens 25% das vezes, com desaceleração brusca
        NAO_COOPERANTE // Ignora as ordens e mantém velocidade
    }

    private Personalidade minhaPersonalidade;
    private final long INTERVALO_CAM = 1000000000L; // 1 segundo em nanossegundos
    private long proximoCamTempo = 0; // Para proteger o ciclo do CAM contra o Multi-Hop
    
    // Lógica de Geocasting e Zonas de Segurança
    private int zonaDecidida = 0;      // Nível de alerta atual (1,2 ou 3)
    private boolean decidiuObedecer = false; //Memória da decisão tomada
    private double ultimaDistancia = -1;

    // Lógica de Multi-Hop
    private boolean jaRetransmitiu = false; 
    private boolean aEsperaDeRetransmitir = false; 
    private long tempoAgendadoParaRetransmitir = 0; 
    private NprDenmMessage mensagemGuardada = null; 
    private final double RADIO_RANGE = 140.0; // Alcance do rádio
    private final long T_MAX_ESPERA = 100000000L; // Tempo máximo de espera para retransmissão (100 ms)

    @Override
    public void onStartup() {
        atribuirPersonalidade(); //Define o tipo de condutor
        pintarCarro(); // Feedback visual no SUMO
        getOs().getAdHocModule().enable(); // Liga o rádio do carro para a comunicação V2X
        
        System.out.println(String.format("[START] Veículo: %-8s | Personalidade: %-16s | Rádio: %s", 
                getOs().getId(), 
                minhaPersonalidade.name(), 
                getOs().getAdHocModule().isEnabled() ? "OK" : "ERRO"));
        
        // Criamos o primeiro evento para o envio do CAM, que se auto-agendará a cada segundo
        proximoCamTempo = getOs().getSimulationTime() + INTERVALO_CAM;
        getOs().getEventManager().addEvent(proximoCamTempo, this);
    }

    @Override
    public void processEvent(Event event) throws Exception { // Método para processar os eventos agendados
        long tempoAtual = getOs().getSimulationTime(); 

        // Evento CAM: Envio periódico de mensagens CAM com a velocidade atual do veículo
        if (tempoAtual >= proximoCamTempo) {
            // Enviamos NprCamMessage com a velocidade atual para a RSU calcular velocidade média
            double velocidadeAtual = getOs().getVehicleData().getSpeed();
            org.eclipse.mosaic.lib.objects.v2x.MessageRouting camRouting =
                    getOs().getAdHocModule().createMessageRouting().topoBroadCast(1);
            getOs().getAdHocModule().sendV2xMessage(new NprCamMessage(camRouting, velocidadeAtual)); 
            // Envio do CAM com a velocidade atual
            proximoCamTempo = tempoAtual + INTERVALO_CAM;
            getOs().getEventManager().addEvent(proximoCamTempo, this); // Reagendamos o próximo alarme para manter a periodicidade
        }

        // Evento de retransmissão: Verificar se é hora de retransmitir a mensagem guardada
        if (aEsperaDeRetransmitir && tempoAtual >= tempoAgendadoParaRetransmitir) { 
            // Se o alarme tocou e ninguém nos cancelou, passamos a mensagem para trás!
            org.eclipse.mosaic.lib.objects.v2x.MessageRouting routing = getOs().getAdHocModule().createMessageRouting().topoBroadCast(1);
            NprDenmMessage msgRetransmitida = new NprDenmMessage(routing, mensagemGuardada.getTempoExpiracao());
            
            getOs().getAdHocModule().sendV2xMessage(msgRetransmitida);
            
            aEsperaDeRetransmitir = false;
            jaRetransmitiu = true; // mecanismo anti-loop: só retransmitimos uma vez por mensagem recebida
            System.out.println(String.format("%-8s RETRANSMITIU o alerta para trás (Multi-Hop)!", getOs().getId()));
        }
    }

    
    // Método para processar mensagens recebidas
    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        V2xMessage msg = receivedV2xMessage.getMessage();
        
        if (msg instanceof NprDenmMessage) { // Alerta por parte da RSU
            NprDenmMessage denm = (NprDenmMessage) msg;

            
            if (getOs().getSimulationTime() > denm.getTempoExpiracao()) {
                return; // Ignorar mensagens expiradas
            }

            // Cálculo da distância entre o emissor da mensagem e o receptor para aplicar os filtros de geocasting
            org.eclipse.mosaic.lib.geo.GeoPoint minhaPos = getOs().getNavigationModule().getCurrentPosition();
            org.eclipse.mosaic.lib.geo.GeoPoint emissorPos = msg.getRouting().getSource().getSourcePosition();
            double distancia = minhaPos.distanceTo(emissorPos);
            String nomeEmissor = msg.getRouting().getSource().getSourceName();

            if (ultimaDistancia != -1.0 && distancia > ultimaDistancia) { // Se a distância começar a aumentar, é sinal de que já passamos pela obra
                if (decidiuObedecer) {
                    getOs().changeSpeedWithPleasantAcceleration(19.44); // Retoma 70 km/h com aceleração suave
                    System.out.println(String.format("%-8s Já passou a obra! Retomando velocidade normal. (Dist: %.1fm)", getOs().getId(), distancia));
                    decidiuObedecer = false;
                    zonaDecidida = 0;
                }
                ultimaDistancia = distancia;
                return;
            }
            ultimaDistancia = distancia;

            // Supressão e Agendamento para Multi-Hop
            if (aEsperaDeRetransmitir) {
                // Supressão: outro nó já retransmitiu, cancelamos o nosso envio
                aEsperaDeRetransmitir = false;
                jaRetransmitiu = true;
                System.out.println(String.format("%-8s SUPRIMIU envio (ouviu o alerta de %s).", getOs().getId(), nomeEmissor));
            } else if (!jaRetransmitiu) {
                // Agendamento: calcular T_wait inversamente proporcional à distância
                double d = Math.min(distancia, RADIO_RANGE);
                long tEspera = (long) (T_MAX_ESPERA * (1.0 - (d / RADIO_RANGE)));
                tEspera += (long) (getRandom().nextDouble() * 10000000L); // jitter 0–10 ms
                tempoAgendadoParaRetransmitir = getOs().getSimulationTime() + tEspera;
                mensagemGuardada = denm;
                aEsperaDeRetransmitir = true;
                getOs().getEventManager().addEvent(tempoAgendadoParaRetransmitir, this); 
                // Agendamos o evento para tentar retransmitir a mensagem após o tempo de espera calculado
                System.out.println(String.format("%-8s AGENDOU retransmissão para %.1f ms (Dist: %.1fm)", getOs().getId(), tEspera / 1000000.0, distancia));
            }

            // Determinar em que zona o veículo se encontra (Funil de 3 zonas)
            int zonaAtual;
            if      (distancia > 500) zonaAtual = 1; // alerta - 70 km/h
            else if (distancia > 200) zonaAtual = 2; // aproximação - 50 km/h
            else                      zonaAtual = 3; // crítica - 30 km/h

            // Novo sorteio independente ao entrar numa zona mais próxima
            if (zonaAtual > zonaDecidida) {
                decidiuObedecer = (getRandom().nextDouble() < getProbabilidade());
                zonaDecidida = zonaAtual;
                System.out.println(String.format("%-8s [%-16s] ZONA %d → %s",
                    getOs().getId(), minhaPersonalidade.name(), zonaAtual,
                    decidiuObedecer ? "OBEDECE" : "IGNORA"));
            }

            // --- FUNIL DE VELOCIDADE ---
            if (decidiuObedecer && zonaAtual > 0) {
                double velocidadeAlvo = (zonaAtual == 1) ? 19.44 :  // 70 km/h
                                        (zonaAtual == 2) ? 13.89 :  // 50 km/h
                                                           8.33;    // 30 km/h

                if (minhaPersonalidade == Personalidade.COOPERANTE) {
                    getOs().changeSpeedWithPleasantAcceleration(velocidadeAlvo);
                    System.out.println(String.format("%-8s [COOPERANTE] Zona %d → %.0f km/h (SUAVE)", getOs().getId(), zonaAtual, velocidadeAlvo * 3.6));
                } else if (minhaPersonalidade == Personalidade.PADRAO) {
                    getOs().changeSpeedWithInterval(velocidadeAlvo, 5000000000L);
                    System.out.println(String.format("%-8s [PADRAO] Zona %d → %.0f km/h", getOs().getId(), zonaAtual, velocidadeAlvo * 3.6));
                } else if (minhaPersonalidade == Personalidade.POUCO_COOPERANTE) {
                    getOs().changeSpeedWithInterval(velocidadeAlvo, 2000000000L);
                    System.out.println(String.format("%-8s [POUCO_COOP] Zona %d → %.0f km/h (BRUSCO)", getOs().getId(), zonaAtual, velocidadeAlvo * 3.6));
                }
            }
        }
    }
    
    @Override
    public void onAcknowledgementReceived(ReceivedAcknowledgement acknowledgedMessage) {}

    @Override
    public void onCamBuilding(CamBuilder camBuilder) {}

    @Override
    public void onMessageTransmitted(V2xMessageTransmission v2xMessageTransmission) {}


    private double getProbabilidade() { // Probabilidade de obedecer às ordens com base na personalidade
        switch (minhaPersonalidade) {
            case COOPERANTE:       return 1.0; // Obedece sempre
            case PADRAO:           return 0.5; // Obedece 50% das vezes
            case POUCO_COOPERANTE: return 0.25; // Obedece 25% das vezes
            default:               return 0.0; // Nunca obedece
        }
    }

    private void atribuirPersonalidade() { // Sorteio para definir a personalidade do veículo
        double sorteio = getRandom().nextDouble();
        if (sorteio < 0.20) { minhaPersonalidade = Personalidade.COOPERANTE; } //20%
        else if (sorteio < 0.85) { minhaPersonalidade = Personalidade.PADRAO; } //65%
        else if (sorteio < 0.95) { minhaPersonalidade = Personalidade.POUCO_COOPERANTE; } //10%
        else { minhaPersonalidade = Personalidade.NAO_COOPERANTE; } //5%
    }

    private void pintarCarro() { // Feedback visual no SUMO com base na personalidade
        VehicleParameters.VehicleParametersChangeRequest mudarCor = getOs().requestVehicleParametersUpdate();
        if (minhaPersonalidade == Personalidade.COOPERANTE) { mudarCor.changeColor(Color.GREEN); }
        else if (minhaPersonalidade == Personalidade.PADRAO) { mudarCor.changeColor(Color.BLUE); }
        else if (minhaPersonalidade == Personalidade.POUCO_COOPERANTE) { mudarCor.changeColor(Color.MAGENTA); }
        else { mudarCor.changeColor(Color.RED); }
        getOs().applyVehicleParametersChange(mudarCor);
    }

    @Override
    public void onVehicleUpdated(VehicleData previousVehicleData, VehicleData updatedVehicleData) {}

    @Override
    public void onShutdown() {
        System.out.println(String.format("\n[ STOP] Veículo: %-8s | Desligado.", getOs().getId()));
    }
}