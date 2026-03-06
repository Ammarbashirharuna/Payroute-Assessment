package com.payroute.repository;
import com.payroute.model.AccountBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ab FROM AccountBalance ab WHERE ab.account.id = :accountId AND ab.currency = :currency")
    Optional<AccountBalance> findByAccountIdAndCurrencyForUpdate(@Param("accountId") UUID accountId, @Param("currency") String currency);

    Optional<AccountBalance> findByAccountIdAndCurrency(UUID accountId, String currency);
    
    List<AccountBalance> findByAccountId(UUID accountId);
}
