module org.lattejava.web.tests {
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.web;
  requires org.testng;
  opens org.lattejava.web.tests to org.testng;
}