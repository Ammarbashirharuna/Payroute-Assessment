package com.payroute.repository;
import com.payroute.model.TransactionStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionStatusHistoryRepository extends JpaRepository<TransactionStatusHistory, UUID> {
    List<TransactionStatusHistory> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
