module org.lattejava.web {
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.jwt;

  exports org.lattejava.web;
  exports org.lattejava.web.json;
  exports org.lattejava.web.middleware;
  exports org.lattejava.web.oidc;
  exports org.lattejava.web.oidc.internal;
}
