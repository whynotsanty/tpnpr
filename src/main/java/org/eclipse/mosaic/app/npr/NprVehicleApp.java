package org.eclipse.mosaic.app.npr;

import java.awt.Color;
import org.eclipse.mosaic.fed.application.ambassador.simulation.VehicleParameters;
import org.eclipse.mosaic.fed.application.app.AbstractApplication;
import org.eclipse.mosaic.fed.application.app.api.VehicleApplication;
import org.eclipse.mosaic.fed.application.app.api.os.VehicleOperatingSystem;
import org.eclipse.mosaic.lib.objects.vehicle.VehicleData;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.util.scheduling.Event;

// IMPORTS DE COMUNICAÇÃO
import org.eclipse.mosaic.fed.application.app.api.CommunicationApplication;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedV2xMessage;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.ReceivedAcknowledgement;
import org.eclipse.mosaic.fed.application.ambassador.simulation.communication.CamBuilder;
import org.eclipse.mosaic.interactions.communication.V2xMessageTransmission;

public class NprVehicleApp extends AbstractApplication<VehicleOperatingSystem> implements VehicleApplication, CommunicationApplication {

    public enum Personalidade {
        COOPERANTE, PADRAO, POUCO_COOPERANTE, NAO_COOPERANTE
    }

    private Personalidade minhaPersonalidade;
    private final long INTERVALO_CAM = 1000000000L; 
    private long proximoCamTempo = 0; // Protege o ciclo do CAM contra o Multi-Hop
    
    // Variáveis de Memória e Geocasting
    private int zonaDecidida = 0;      // 0=nenhuma, 1=alerta(500-1000m), 2=aprox.(200-500m), 3=crítica(<200m)
    private boolean decidiuObedecer = false;
    private double ultimaDistancia = -1;

    // --- SPRINT 3: VARIÁVEIS DO MULTI-HOP (BACK-OFF E SUPRESSÃO) ---
    private boolean jaRetransmitiu = false; 
    private boolean aEsperaDeRetransmitir = false; 
    private long tempoAgendadoParaRetransmitir = 0; 
    private NprDenmMessage mensagemGuardada = null; 
    private final double RADIO_RANGE = 140.0; // Metros (O R da vossa fórmula)
    private final long T_MAX_ESPERA = 100000000L; // 100 ms (O T_max da vossa fórmula)

    @Override
    public void onStartup() {
        atribuirPersonalidade();
        pintarCarro();
        getOs().getAdHocModule().enable(); 
        
        System.out.println(String.format("[START] Veículo: %-8s | Personalidade: %-16s | Rádio: %s", 
                getOs().getId(), 
                minhaPersonalidade.name(), 
                getOs().getAdHocModule().isEnabled() ? "OK" : "ERRO"));
        
        // Iniciamos o ciclo do CAM de forma segura
        proximoCamTempo = getOs().getSimulationTime() + INTERVALO_CAM;
        getOs().getEventManager().addEvent(proximoCamTempo, this);
    }

    @Override
    public void processEvent(Event event) throws Exception {
        long tempoAtual = getOs().getSimulationTime();

        // 1. O EVENTO É DO CAM? (O relógio chegou à hora de enviar CAM)
        if (tempoAtual >= proximoCamTempo) {
            getOs().getAdHocModule().sendCam();
            proximoCamTempo = tempoAtual + INTERVALO_CAM;
            getOs().getEventManager().addEvent(proximoCamTempo, this);
        }

        // 2. O EVENTO É O DESPERTADOR DE RETRANSMISSÃO (Sprint 3)
        if (aEsperaDeRetransmitir && tempoAtual >= tempoAgendadoParaRetransmitir) {
            // Se o alarme tocou e ninguém nos cancelou, passamos a mensagem para trás!
            org.eclipse.mosaic.lib.objects.v2x.MessageRouting routing = getOs().getAdHocModule().createMessageRouting().topoBroadCast(1);
            NprDenmMessage msgRetransmitida = new NprDenmMessage(routing, mensagemGuardada.getVelocidadeRecomendada(), mensagemGuardada.getTempoExpiracao());
            
            getOs().getAdHocModule().sendV2xMessage(msgRetransmitida);
            
            aEsperaDeRetransmitir = false;
            jaRetransmitiu = true; // Só fazemos o papel de repetidor uma vez
            System.out.println(String.format("📡 %-8s RETRANSMITIU o alerta para trás (Multi-Hop)!", getOs().getId()));
        }
    }

    // --- MÉTODOS DE COMUNICAÇÃO ---

    @Override
    public void onMessageReceived(ReceivedV2xMessage receivedV2xMessage) {
        V2xMessage msg = receivedV2xMessage.getMessage();
        
        if (msg instanceof NprDenmMessage) {
            NprDenmMessage denm = (NprDenmMessage) msg;

            // --- FILTRO 1: TTL (VALIDADE) ---
            if (getOs().getSimulationTime() > denm.getTempoExpiracao()) {
                return;
            }

            // Cálculo da distância e identificação do emissor
            org.eclipse.mosaic.lib.geo.GeoPoint minhaPos = getOs().getNavigationModule().getCurrentPosition();
            org.eclipse.mosaic.lib.geo.GeoPoint emissorPos = msg.getRouting().getSource().getSourcePosition();
            double distancia = minhaPos.distanceTo(emissorPos);
            String nomeEmissor = msg.getRouting().getSource().getSourceName();

            // --- FILTRO 2: GEOCASTING DIRECIONAL ---
            // (verificado ANTES do multi-hop para não agendar retransmissões desnecessárias)
            if (ultimaDistancia != -1.0 && distancia > ultimaDistancia) {
                if (decidiuObedecer) {
                    getOs().changeSpeedWithPleasantAcceleration(33.33);
                    System.out.println(String.format("%-8s Já passou a obra! Retomando velocidade normal. (Dist: %.1fm)", getOs().getId(), distancia));
                    decidiuObedecer = false;
                    zonaDecidida = 0;
                }
                ultimaDistancia = distancia;
                return;
            }
            ultimaDistancia = distancia;

            // --- MULTI-HOP: SUPRESSÃO E AGENDAMENTO DE BACK-OFF ---
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
            }

            // --- SORTEIO POR ZONA ---
            // Determinar em que zona o veículo se encontra (3 zonas)
            int zonaAtual;
            if      (distancia > 500) zonaAtual = 1; // alerta      — 70 km/h
            else if (distancia > 200) zonaAtual = 2; // aproximação — 50 km/h
            else                      zonaAtual = 3; // crítica     — 30 km/h

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

    // --- MÉTODOS DE LÓGICA DO CARRO ---

    private double getProbabilidade() {
        switch (minhaPersonalidade) {
            case COOPERANTE:       return 1.0;
            case PADRAO:           return 0.5;
            case POUCO_COOPERANTE: return 0.25;
            default:               return 0.0; // NAO_COOPERANTE
        }
    }

    private void atribuirPersonalidade() {
        double sorteio = getRandom().nextDouble();
        if (sorteio < 0.20) { minhaPersonalidade = Personalidade.COOPERANTE; } //20%
        else if (sorteio < 0.85) { minhaPersonalidade = Personalidade.PADRAO; } //65%
        else if (sorteio < 0.95) { minhaPersonalidade = Personalidade.POUCO_COOPERANTE; } //10%
        else { minhaPersonalidade = Personalidade.NAO_COOPERANTE; } //5%
    }

    private void pintarCarro() {
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