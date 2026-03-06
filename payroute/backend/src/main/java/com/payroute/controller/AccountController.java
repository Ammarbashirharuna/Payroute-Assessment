package com.payroute.controller;

import com.payroute.model.Account;
import com.payroute.model.AccountBalance;
import com.payroute.repository.AccountBalanceRepository;
import com.payroute.repository.AccountRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accountRepo;
    private final AccountBalanceRepository balanceRepo;

    @GetMapping
    public ResponseEntity<List<AccountDTO>> listAccounts() {
        List<Account> accounts = accountRepo.findByAccountTypeAndIsActiveTrue("customer");
        List<AccountDTO> dtos = accounts.stream().map(a -> {
            AccountDTO dto = new AccountDTO();
            dto.setId(a.getId());
            dto.setOwnerName(a.getOwnerName());
            dto.setOwnerEmail(a.getOwnerEmail());
            dto.setAccountType(a.getAccountType());
            List<BalanceDTO> balances = balanceRepo.findByAccountId(a.getId()).stream().map(b -> {
                BalanceDTO bd = new BalanceDTO();
                bd.setCurrency(b.getCurrency());
                bd.setBalance(b.getBalance());
                return bd;
            }).toList();
            dto.setBalances(balances);
            return dto;
        }).toList();
        return ResponseEntity.ok(dtos);
    }

    @Data
    static class AccountDTO {
        UUID id;
        String ownerName;
        String ownerEmail;
        String accountType;
        List<BalanceDTO> balances;
    }

    @Data
    static class BalanceDTO {
        String currency;
        BigDecimal balance;
    }
}
