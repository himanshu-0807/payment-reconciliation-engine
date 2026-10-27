package dev.recon.common.soap;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"utr", "status", "creditCount", "amountMinor", "currency", "valueDate", "bankRef"})
@XmlRootElement(name = "getSettlementStatusResponse", namespace = SettlementSoap.NAMESPACE)
public class GetSettlementStatusResponse {

    @XmlElement(namespace = SettlementSoap.NAMESPACE, required = true)
    private String utr;
    @XmlElement(namespace = SettlementSoap.NAMESPACE, required = true)
    private String status;
    @XmlElement(namespace = SettlementSoap.NAMESPACE)
    private int creditCount;
    @XmlElement(namespace = SettlementSoap.NAMESPACE)
    private long amountMinor;
    @XmlElement(namespace = SettlementSoap.NAMESPACE, required = true)
    private String currency;
    @XmlElement(namespace = SettlementSoap.NAMESPACE)
    private String valueDate;
    @XmlElement(namespace = SettlementSoap.NAMESPACE)
    private String bankRef;

    public String getUtr() { return utr; }
    public void setUtr(String utr) { this.utr = utr; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getCreditCount() { return creditCount; }
    public void setCreditCount(int creditCount) { this.creditCount = creditCount; }
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getValueDate() { return valueDate; }
    public void setValueDate(String valueDate) { this.valueDate = valueDate; }
    public String getBankRef() { return bankRef; }
    public void setBankRef(String bankRef) { this.bankRef = bankRef; }
}
