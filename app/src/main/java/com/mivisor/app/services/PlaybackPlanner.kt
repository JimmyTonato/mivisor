package com.mivisor.app.services

/**
 * "Ver ahora / OK = quiero ver esto". Antes de abrir el reproductor, MiVisor resuelve solo lo que pueda
 * (iniciar Ace Stream, conectar la VPN) y SOLO pregunta cuando hace falta una persona (autorizar la VPN,
 * un fallo). Ace y VPN se preparan a la vez: nada de cascadas de diálogos.
 *
 * Lógica pura: recibe el estado real de los servicios y dice qué hacer. Se prueba en la JVM.
 */
data class PlaybackNeeds(
    /** El enlace es de Ace Stream (acestream:// o el motor local). */
    val ace: Boolean,
    /** El proveedor está marcado como "Usar VPN". */
    val vpn: Boolean
) {
    val any: Boolean get() = ace || vpn
}

enum class StepState { WAITING, WORKING, DONE }

enum class PrepProblem {
    ACE_FAILED,
    VPN_AUTH,
    VPN_SETUP,
    VPN_FAILED,
    VPN_MISSING
}

sealed class PrepDecision {
    /** Todo listo: abrir el reproductor. */
    object Launch : PrepDecision()

    /** Trabajando: qué pedir ahora (cada orden se da una sola vez) y cómo va cada paso. */
    data class Work(
        val startAce: Boolean,
        val connectVpn: Boolean,
        val ace: StepState?,
        val vpn: StepState?
    ) : PrepDecision()

    /** Hace falta el usuario. */
    data class Ask(val problem: PrepProblem) : PrepDecision()
}

class PlaybackPlanner(val needs: PlaybackNeeds) {
    private var aceRequested = false
    private var vpnRequested = false
    private var vpnSawProgress = false
    /** El usuario pulsó "Autorizar": mientras Android pide permiso no se vuelve a preguntar. */
    private var awaitingAuth = false

    fun next(ace: AceStatus, warp: WarpStatus): PrepDecision {
        var startAce = false
        var connectVpn = false
        var ask: PrepProblem? = null

        val aceStep: StepState? = if (!needs.ace) null else when (ace.phase) {
            AcePhase.READY -> StepState.DONE
            // Sin Ace Stream instalado se deja al reproductor externo (puede abrir acestream:// por su cuenta).
            AcePhase.NOT_INSTALLED -> StepState.DONE
            AcePhase.CHECKING, AcePhase.STARTING -> StepState.WORKING
            AcePhase.STOPPED -> {
                if (!aceRequested) { aceRequested = true; startAce = true }
                StepState.WORKING
            }
            AcePhase.ERROR -> if (!aceRequested) {
                aceRequested = true; startAce = true                    // un reintento automático
                StepState.WORKING
            } else {
                ask = PrepProblem.ACE_FAILED
                StepState.WAITING
            }
        }

        val vpnStep: StepState? = if (!needs.vpn) null else when (warp.phase) {
            WarpPhase.CONNECTED -> { awaitingAuth = false; StepState.DONE }
            WarpPhase.CHECKING -> StepState.WORKING
            WarpPhase.CONNECTING -> { vpnSawProgress = true; StepState.WORKING }
            WarpPhase.DISCONNECTING -> StepState.WORKING
            WarpPhase.DISCONNECTED -> when {
                !vpnRequested -> { vpnRequested = true; connectVpn = true; StepState.WORKING }
                vpnSawProgress -> { ask = ask ?: PrepProblem.VPN_FAILED; StepState.WAITING }
                else -> StepState.WORKING                                 // la orden acaba de salir
            }
            WarpPhase.PERMISSION_REQUIRED -> if (awaitingAuth) StepState.WORKING else {
                ask = PrepProblem.VPN_AUTH; StepState.WAITING
            }
            WarpPhase.NEEDS_SETUP -> { ask = PrepProblem.VPN_SETUP; StepState.WAITING }
            WarpPhase.ERROR -> if (!vpnRequested) {
                vpnRequested = true; connectVpn = true; StepState.WORKING
            } else {
                ask = PrepProblem.VPN_FAILED; StepState.WAITING
            }
            WarpPhase.NOT_INSTALLED, WarpPhase.SIGNATURE_MISMATCH -> { ask = PrepProblem.VPN_MISSING; StepState.WAITING }
        }

        // Si hay que preguntar algo de la VPN, se pregunta antes que lo de Ace (la VPN suele condicionar el resto).
        val problem = ask
        if (problem != null) {
            // Las órdenes ya decididas se dan igualmente: Ace puede ir arrancando mientras el usuario autoriza.
            return if (startAce || connectVpn) PrepDecision.Work(startAce, connectVpn, aceStep, vpnStep).also { pendingAsk = problem }
            else PrepDecision.Ask(problem)
        }
        pendingAsk = null
        if ((aceStep == null || aceStep == StepState.DONE) && (vpnStep == null || vpnStep == StepState.DONE)) return PrepDecision.Launch
        return PrepDecision.Work(startAce, connectVpn, aceStep, vpnStep)
    }

    /** Pregunta pendiente que no se devolvió porque en ese mismo paso había órdenes que dar. */
    var pendingAsk: PrepProblem? = null
        private set

    /** "Autorizar": se abre la pantalla de Android; hasta volver, no se repite la pregunta. */
    fun onAuthorizeRequested() { awaitingAuth = true }

    /** Al volver a MiVisor (onResume): si sigue sin permiso, se vuelve a preguntar. */
    fun onForeground() { awaitingAuth = false }

    /** "Reintentar": se vuelve a dar la orden del paso que falló. */
    fun retry(problem: PrepProblem) {
        when (problem) {
            PrepProblem.ACE_FAILED -> aceRequested = false
            PrepProblem.VPN_FAILED -> { vpnRequested = false; vpnSawProgress = false }
            else -> Unit
        }
    }
}
