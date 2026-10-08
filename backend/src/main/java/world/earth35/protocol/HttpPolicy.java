package world.earth35.protocol;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class HttpPolicy extends OncePerRequestFilter {
    private final String origin;
    private final ConcurrentHashMap<String,long[]> windows=new ConcurrentHashMap<>();
    public HttpPolicy(@Value("${earth35.web-origin}") String origin) { this.origin=origin; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        res.setHeader("X-Content-Type-Options","nosniff");
        res.setHeader("Referrer-Policy","same-origin");
        if(req.getRequestURI().startsWith("/v1/")) {
            res.setHeader("Cache-Control","no-store");
            String given=req.getHeader("Origin");
            boolean write=!req.getMethod().equals("GET")&&!req.getMethod().equals("OPTIONS");
            String loopback=origin.replace("localhost","127.0.0.1");
            if(given!=null&&!origin.equals(given)&&!loopback.equals(given)) { fail(res,403,"Origin is not allowed");return; }
            if(write&&!"agent".equals(req.getHeader("X-E35-Client"))&&!"web".equals(req.getHeader("X-E35-Client"))) { fail(res,403,"X-E35-Client header required");return; }
            if(req.getContentLengthLong()>32768) { fail(res,413,"Request is too large");return; }
            boolean auth=req.getRequestURI().startsWith("/v1/auth/")&&write;
            String key=req.getRemoteAddr()+":"+auth;
            long now=System.currentTimeMillis()/60000;
            long[] bucket=windows.compute(key,(k,v)->v==null||v[0]!=now?new long[]{now,1}:new long[]{now,v[1]+1});
            if(windows.size()>2000) windows.entrySet().removeIf(e->e.getValue()[0]<now);
            if(bucket[1]>(auth?30:12000)) { res.setHeader("Retry-After","60");fail(res,429,"Rate limit exceeded");return; }
        }
        chain.doFilter(req,res);
    }
    private void fail(HttpServletResponse res,int status,String message) throws IOException { res.setStatus(status);res.setContentType("application/json");res.getWriter().write("{\"error\":\""+message+"\",\"status\":"+status+"}"); }
}
