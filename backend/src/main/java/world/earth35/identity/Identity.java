package world.earth35.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import world.earth35.protocol.Contracts.Credentials;

@Service
public class Identity {
    private final JdbcTemplate db;
    private final Pbkdf2PasswordEncoder passwords=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    public Identity(JdbcTemplate db) { this.db=db; }
    public static String token() { byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) {throw new IllegalStateException(e);} }
    public static ResponseStatusException error(HttpStatus status,String text) { return new ResponseStatusException(status,text); }
    @Transactional public String session(Credentials input,boolean register) {
        String email=input.email().strip().toLowerCase(Locale.ROOT);
        String owner;
        if(register) {
            owner=UUID.randomUUID().toString();
            int inserted=db.update("INSERT INTO owners(id,email,password_hash) VALUES(?,?,?) ON CONFLICT(email) DO NOTHING",owner,email,passwords.encode(input.password()));
            if(inserted==0) throw error(HttpStatus.CONFLICT,"Email already registered");
        } else {
            var rows=db.queryForList("SELECT id,password_hash FROM owners WHERE email=?",email);
            if(rows.isEmpty()||!passwords.matches(input.password(),(String)rows.getFirst().get("password_hash"))) throw error(HttpStatus.UNAUTHORIZED,"Invalid email or password");
            owner=(String)rows.getFirst().get("id");
        }
        String token=token();
        db.update("DELETE FROM sessions WHERE expires_at<now()");
        db.update("INSERT INTO sessions VALUES(?,?,now()+interval '7 days')",hash(token),owner);
        return token;
    }
    public String owner(HttpServletRequest request) {
        if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(cookie.getName().equals("e35_session")) {
            var rows=db.queryForList("SELECT owner_id FROM sessions WHERE token_hash=? AND expires_at>now()",String.class,hash(cookie.getValue()));
            if(!rows.isEmpty()) return rows.getFirst();
        }
        throw error(HttpStatus.UNAUTHORIZED,"Sign in to manage your agents");
    }
    public String agent(HttpServletRequest request) {
        String auth=request.getHeader("Authorization");
        if(auth!=null&&auth.startsWith("Bearer ")) {
            var rows=db.queryForList("SELECT agent_id FROM agent_credentials WHERE token_hash=?",String.class,hash(auth.substring(7)));
            if(!rows.isEmpty()) return rows.getFirst();
        }
        throw error(HttpStatus.UNAUTHORIZED,"A valid agent bearer token is required");
    }
    public void logout(HttpServletRequest request) {
        if(request.getCookies()!=null) for(var c:request.getCookies()) if(c.getName().equals("e35_session")) db.update("DELETE FROM sessions WHERE token_hash=?",hash(c.getValue()));
    }
}
