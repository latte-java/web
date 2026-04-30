module org.lattejava.web {
  requires com.fasterxml.jackson.databind;
  requires gg.jte;
  requires gg.jte.runtime;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.jwt;

  exports org.lattejava.web;
  exports org.lattejava.web.json;
  exports org.lattejava.web.jte;
  exports org.lattejava.web.log;
  exports org.lattejava.web.middleware;
  exports org.lattejava.web.oidc;
  exports org.lattejava.web.oidc.internal;
  exports org.lattejava.web.test;
}
