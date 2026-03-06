package com.payroute.repository;

import com.payroute.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByReference(String reference);
    Optional<Transaction> findByProviderReference(String providerReference);
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Page<Transaction> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<Transaction> findByStatusOrderByCreatedAtDesc(Transaction.Status status, Pageable pageable);
    Page<Transaction> findByCreatedAtBetweenOrderByCreatedAtDesc(OffsetDateTime from, OffsetDateTime to, Pageable pageable);
    Page<Transaction> findByStatusAndCreatedAtBetweenOrderByCreatedAtDesc(Transaction.Status status, OffsetDateTime from, OffsetDateTime to, Pageable pageable);
}