@import org.lattejava.http.server.HTTPRequest
@import org.lattejava.web.Messages
@param HTTPRequest request
<h1>${new Messages(request).get("title")}</h1><p>${new Messages(request).get("greeting", "<Brian>")}</p>
