@import org.lattejava.http.server.HTTPRequest
@import org.lattejava.web.Flash
@param HTTPRequest request
@for(var entry : new Flash(request).messages().entrySet())@for(String message : entry.getValue())<li class="${entry.getKey()}">${message}</li>@endfor@endfor