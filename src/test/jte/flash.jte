@import org.lattejava.http.server.HTTPRequest
@import org.lattejava.web.Flash
@param HTTPRequest request
@for(String message : new Flash(request).messages())<li>${message}</li>@endfor