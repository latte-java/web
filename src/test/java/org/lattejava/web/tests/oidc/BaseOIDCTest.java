package org.lattejava.web.tests.oidc;

import org.lattejava.jwt.*;
import org.lattejava.web.oidc.*;
import org.lattejava.web.tests.*;
import org.testng.annotations.*;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class BaseOIDCTest extends BaseWebTest {
  public static final FusionAuthFixture FIXTURE = new FusionAuthFixture();
  public static OIDC<String> oidc;

  @BeforeSuite
  public static void setupOIDC() {
    try {
      var config = OIDCConfig.builder()
                             .issuer(STANDARD_ISSUER)
                             .clientId(STANDARD_APP_ID)
                             .clientSecret(STANDARD_APP_SECRET)
                             .build();
      oidc = OIDC.create(config, JWT::subject);
    } catch (Exception e) {
      System.out.println("Unable to construct the OIDC configuration and the OIDC instance. This is likely due to FusionAuth not running.");
      fail("Unable to construct the OIDC configuration and the OIDC instance. This is likely due to FusionAuth not running.", e);
    }
  }
}
