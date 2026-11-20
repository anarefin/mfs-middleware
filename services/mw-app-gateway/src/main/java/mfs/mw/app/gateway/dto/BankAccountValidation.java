package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * mw-adapter-bank's answer to an account validation (contract {@code AccountValidation} of {@code
 * api/mw-adapter-bank/bank.v1.openapi.yaml}, ticket 117b): last 4 digits and the first initial;
 * {@code nameMatch} when the request named the KYC name (M9 review owner binding), else null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankAccountValidation(String bankCode, String accountLast4, String holderName, Boolean nameMatch) {}
