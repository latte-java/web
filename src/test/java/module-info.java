module org.lattejava.web.tests {
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.web;
  requires org.testng;

  opens org.lattejava.web.tests to com.fasterxml.jackson.databind, org.testng;
  opens org.lattejava.web.tests.middleware to com.fasterxml.jackson.databind, org.testng;
  opens org.lattejava.web.tests.oidc to com.fasterxml.jackson.databind, org.testng;
}