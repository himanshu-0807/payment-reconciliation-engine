package dev.recon.common.soap;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"utr"})
@XmlRootElement(name = "getSettlementStatusRequest", namespace = SettlementSoap.NAMESPACE)
public class GetSettlementStatusRequest {

    @XmlElement(namespace = SettlementSoap.NAMESPACE, required = true)
    private String utr;

    public GetSettlementStatusRequest() {
    }

    public GetSettlementStatusRequest(String utr) {
        this.utr = utr;
    }

    public String getUtr() {
        return utr;
    }

    public void setUtr(String utr) {
        this.utr = utr;
    }
}
