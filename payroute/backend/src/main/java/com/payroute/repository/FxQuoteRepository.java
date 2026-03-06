package com.payroute.repository;
import com.payroute.model.FxQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface FxQuoteRepository extends JpaRepository<FxQuote, UUID> {}
