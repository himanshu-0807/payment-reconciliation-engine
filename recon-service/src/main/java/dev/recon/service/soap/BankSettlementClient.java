package dev.recon.service.soap;

import dev.recon.common.soap.GetSettlementStatusRequest;
import dev.recon.common.soap.GetSettlementStatusResponse;
import dev.recon.service.api.Dtos;
import dev.recon.service.api.UpstreamException;
import dev.recon.service.config.ReconProperties;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceClientException;
import org.springframework.ws.client.core.WebServiceTemplate;

/** SOAP client for bank-sim's getSettlementStatus operation (Spring Web Services + JAXB). */
@Component
public class BankSettlementClient {

    private final WebServiceTemplate ws;
    private final String url;

    public BankSettlementClient(ReconProperties props) {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setClassesToBeBound(GetSettlementStatusRequest.class, GetSettlementStatusResponse.class);
        this.ws = new WebServiceTemplate(marshaller);
        this.url = props.bankSoapUrl();
    }

    public Dtos.BankStatus status(String utr) {
        try {
            GetSettlementStatusResponse r = (GetSettlementStatusResponse) ws.marshalSendAndReceive(url,
                    new GetSettlementStatusRequest(utr));
            return new Dtos.BankStatus(r.getUtr(), r.getStatus(), r.getCreditCount(), r.getAmountMinor(), r.getCurrency(),
                    r.getValueDate(), r.getBankRef(), "SOAP " + url);
        } catch (WebServiceClientException e) {
            throw new UpstreamException("bank settlement SOAP call failed: " + e.getMessage(), e);
        }
    }
}
