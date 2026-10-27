package dev.recon.bank.sim;

import dev.recon.common.sim.SimRunRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class BankSimController {

    private final BankSimulator simulator;

    public BankSimController(BankSimulator simulator) {
        this.simulator = simulator;
    }

    /** Called by gateway-sim with the same config, so both sides derive the same world. */
    @PostMapping("/sim/bank/generate")
    public BankSimulator.Result generate(@RequestBody SimRunRequest request) throws IOException {
        return simulator.generate(request);
    }
}
