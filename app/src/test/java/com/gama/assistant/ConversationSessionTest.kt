package com.gama.assistant

import org.junit.Assert.*
import org.junit.Test

class ConversationSessionTest {
    @Test fun oneWakeKeepsSessionOpen() {
        val session = ConversationSession()
        assertFalse(session.active)
        session.open()
        assertTrue(session.active)
        session.userSaid("Tenho uma reunião às 14h")
        session.gamaSaid("Sua reunião é às 14h")
        session.userSaid("e depois?")
        val history = session.historyBeforeCurrent()
        assertTrue(history.contains("Tenho uma reunião às 14h"))
        assertTrue(history.contains("Gama: Sua reunião é às 14h"))
        assertFalse(history.contains("e depois?"))
        session.gamaSaid("Depois, sem compromissos conhecidos")
        assertTrue(session.active)
    }

    @Test fun explicitGoodbyeOnly() {
        listOf(
            "Tchau",
            "Gama, pode descansar",
            "você pode descansar",
            "pode descansar por enquanto",
            "encerrar conversa",
            "Pare de ouvir",
            "pode descansa"
        ).forEach {
            assertTrue(it, ConversationSession.isGoodbye(it))
        }
        listOf(
            "e depois?",
            "posso parar para pensar?",
            "quais os benefícios de descansar?",
            "me fale mais",
            "parar a música",
            "como encerrar conversa?",
            "o que significa pare de ouvir?",
            "descansa",
            "descansar",
            "preciso descansar amanhã",
            "você acha que eu devo descansar?"
        ).forEach {
            assertFalse(it, ConversationSession.isGoodbye(it))
        }
    }

    @Test fun closingClearsTemporaryContext() {
        val session = ConversationSession()
        session.open(); session.userSaid("informação da conversa")
        session.close(); assertFalse(session.active)
        session.open(); session.userSaid("nova pergunta")
        assertFalse(session.historyBeforeCurrent().contains("informação da conversa"))
    }

    @Test fun historyIsBounded() {
        val session = ConversationSession(); session.open()
        repeat(40) { session.userSaid("pergunta $it"); session.gamaSaid("resposta $it") }
        assertFalse(session.historyBeforeCurrent().contains("pergunta 0"))
        assertTrue(session.historyBeforeCurrent().contains("resposta 38"))
    }
    @Test fun longConversationKeepsRecentContextWithinBudget() {
        val session = ConversationSession(); session.open()
        repeat(30) {
            session.userSaid("pergunta longa $it sobre o mesmo assunto e seus detalhes")
            session.gamaSaid("resposta longa $it mantendo o assunto e o contexto anterior")
        }
        session.userSaid("e como isso se conecta ao que falamos?")
        val history = session.historyBeforeCurrent(4200)
        assertTrue(history.length <= 4200)
        assertTrue(history.contains("resposta longa 29"))
        assertTrue(session.active)
    }

    @Test fun historyOnlyForRealFollowUps() {
        assertTrue(ConversationSession.needsHistory("e depois?"))
        assertTrue(ConversationSession.needsHistory("onde ele nasceu?"))
        assertTrue(ConversationSession.needsHistory("e em que ano?"))
        assertFalse(ConversationSession.needsHistory("qual a capital do Chile?"))
        assertFalse(ConversationSession.needsHistory("mande mensagem para Joao"))
    }

}
