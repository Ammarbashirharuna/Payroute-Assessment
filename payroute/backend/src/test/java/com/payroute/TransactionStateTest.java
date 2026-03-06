package com.payroute;

import com.payroute.model.Transaction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransactionStateTest {

    @Test
    void initiated_can_transition_to_processing() {
        assertTrue(Transaction.isValidTransition(Transaction.Status.initiated, Transaction.Status.processing));
    }

    @Test
    void initiated_can_transition_to_failed() {
        assertTrue(Transaction.isValidTransition(Transaction.Status.initiated, Transaction.Status.failed));
    }

    @Test
    void processing_can_transition_to_completed() {
        assertTrue(Transaction.isValidTransition(Transaction.Status.processing, Transaction.Status.completed));
    }

    @Test
    void processing_can_transition_to_failed() {
        assertTrue(Transaction.isValidTransition(Transaction.Status.processing, Transaction.Status.failed));
    }

    @Test
    void completed_cannot_transition_to_anything() {
        assertFalse(Transaction.isValidTransition(Transaction.Status.completed, Transaction.Status.failed));
        assertFalse(Transaction.isValidTransition(Transaction.Status.completed, Transaction.Status.reversed));
        assertFalse(Transaction.isValidTransition(Transaction.Status.completed, Transaction.Status.processing));
    }

    @Test
    void reversed_is_terminal() {
        assertFalse(Transaction.isValidTransition(Transaction.Status.reversed, Transaction.Status.completed));
        assertFalse(Transaction.isValidTransition(Transaction.Status.reversed, Transaction.Status.failed));
    }

    @Test
    void processing_cannot_go_back_to_initiated() {
        assertFalse(Transaction.isValidTransition(Transaction.Status.processing, Transaction.Status.initiated));
    }
}
