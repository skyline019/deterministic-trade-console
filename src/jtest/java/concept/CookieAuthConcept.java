package concept;

import com.acme.trade.adapter.security.cookie.CookieAuthSupport;
import jtest.Concept;
import jtest.JTestRegistry;

import static jtest.JExpect.expectEquals;
import static jtest.JExpect.expectFalse;
import static jtest.JExpect.expectTrue;

public final class CookieAuthConcept implements Concept {
    @Override
    public String name() {
        return "CookieAuthDelivery";
    }

    @Override
    public void register(JTestRegistry tests) {
        tests.test("cookie_header_parser_and_delivery_flags_are_exact", () -> {
            expectTrue(CookieAuthSupport.isCookieDelivery("cookie"));
            expectTrue(CookieAuthSupport.isCookieDelivery("COOKIE"));
            expectFalse(CookieAuthSupport.isCookieDelivery("bearer"));
            expectFalse(CookieAuthSupport.isCookieDelivery(null));

            String header = "__Host-TradeSession=abc; TradeXsrf=deadbeef";
            expectEquals("abc", CookieAuthSupport.readHeader(header, CookieAuthSupport.SESSION_COOKIE).orElseThrow());
            expectEquals("deadbeef", CookieAuthSupport.readHeader(header, CookieAuthSupport.XSRF_COOKIE).orElseThrow());
            expectTrue(CookieAuthSupport.readHeader(header, "missing").isEmpty());

            String xsrf = CookieAuthSupport.newXsrfToken();
            expectEquals(64, xsrf.length());
        });
    }
}
