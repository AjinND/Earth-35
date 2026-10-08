import { NextRequest } from "next/server";
async function proxy(request:NextRequest,{params}:{params:Promise<{path:string[]}>}) {
  const {path}=await params;
  if(path[0]!=="v1") return Response.json({error:"Not found"},{status:404});
  const headers=new Headers();
  for(const name of ["content-type","cookie","authorization","x-e35-client","origin"]) {const value=request.headers.get(name);if(value) headers.set(name,value);}
  try {
    const providerCheck=request.method==="POST"&&path.join("/")==="v1/agents"||request.method==="PUT"&&path.at(-1)==="runtime";
    const response=await fetch(`${process.env.API_URL||"http://localhost:8080"}/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`,{method:request.method,headers,body:["GET","HEAD"].includes(request.method)?undefined:await request.text(),cache:"no-store",signal:AbortSignal.timeout(providerCheck?40000:15000)});
    const output=new Headers({"Content-Type":response.headers.get("content-type")||"application/json","Cache-Control":"no-store"});
    for(const cookie of response.headers.getSetCookie()) output.append("Set-Cookie",cookie);
    return new Response(response.body,{status:response.status,headers:output});
  } catch {return Response.json({error:"The world server is unavailable. Check that the API and database are running."},{status:503});}
}
export {proxy as GET,proxy as POST,proxy as PUT,proxy as DELETE};
