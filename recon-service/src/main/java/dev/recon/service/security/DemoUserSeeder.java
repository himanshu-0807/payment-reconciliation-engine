package dev.recon.service.security;

import dev.recon.service.domain.AppUser;
import dev.recon.service.domain.Repositories.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds one demo user per role on first start, with passwords taken from the environment. */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final SecurityProperties props;

    public DemoUserSeeder(AppUserRepository users, PasswordEncoder encoder, SecurityProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        SecurityProperties.DemoUsers d = props.demoUsers();
        if (d == null) {
            log.warn("no demo user passwords configured; skipping user seeding");
            return;
        }
        seed("analyst", d.analystPassword(), AppUser.Role.OPS_ANALYST, "Demo Analyst");
        seed("supervisor", d.supervisorPassword(), AppUser.Role.OPS_SUPERVISOR, "Demo Supervisor");
        seed("admin", d.adminPassword(), AppUser.Role.ADMIN, "Demo Admin");
    }

    private void seed(String username, String password, AppUser.Role role, String name) {
        if (password == null || password.isBlank()) {
            log.warn("password for demo user '{}' not set; not seeding it", username);
            return;
        }
        if (!users.existsById(username)) {
            users.save(new AppUser(username, encoder.encode(password), role, name));
            log.info("seeded demo user '{}' with role {}", username, role);
        }
    }
}
