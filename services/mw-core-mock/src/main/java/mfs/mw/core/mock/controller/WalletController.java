package mfs.mw.core.mock.controller;

import mfs.mw.core.mock.dto.WalletBalance;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import mfs.mw.core.mock.service.WalletBalances;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test/admin API of the mock (not a product contract): a wallet's balances, read by black-box
 * tests to prove money moved exactly once (MVP acceptance criterion 2).
 */
@RestController
@RequestMapping("/core-mock/v1/wallets")
public class WalletController {

  private final WalletBalances balances;

  public WalletController(WalletBalances balances) {
    this.balances = balances;
  }

  @GetMapping("/{walletId}")
  WalletBalance balance(@PathVariable String walletId) {
    return balances
        .find(walletId)
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "wallet not found"));
  }
}
