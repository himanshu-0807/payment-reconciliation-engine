package dev.recon.bank.soap;

import dev.recon.bank.sim.BankSimulator;
import dev.recon.common.soap.GetSettlementStatusRequest;
import dev.recon.common.soap.GetSettlementStatusResponse;
import dev.recon.common.soap.SettlementSoap;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/** SOAP operation getSettlementStatus(utr): has the bank credited this settlement reference? */
@Endpoint
public class SettlementStatusEndpoint {

    private final BankSimulator bank;

    public SettlementStatusEndpoint(BankSimulator bank) {
        this.bank = bank;
    }

    @PayloadRoot(namespace = SettlementSoap.NAMESPACE, localPart = "getSettlementStatusRequest")
    @ResponsePayload
    public GetSettlementStatusResponse getSettlementStatus(@RequestPayload GetSettlementStatusRequest request) {
        BankSimulator.UtrInfo info = request.getUtr() == null ? null : bank.lookup(request.getUtr().trim());
        GetSettlementStatusResponse r = new GetSettlementStatusResponse();
        r.setUtr(request.getUtr());
        r.setCurrency("INR");
        if (info == null) {
            r.setStatus("NOT_FOUND");
            r.setCreditCount(0);
            r.setAmountMinor(0);
        } else {
            r.setStatus(info.credits() > 1 ? "DUPLICATE_CREDIT" : "CREDITED");
            r.setCreditCount(info.credits());
            r.setAmountMinor(info.amountMinor());
            r.setCurrency(info.currency());
            r.setValueDate(info.valueDate().toString());
            r.setBankRef(info.bankRef());
        }
        return r;
    }
}
