import {NextRequest} from "next/server";
export function GET(request:NextRequest) {
  const publicUrl=process.env.WORLD_WS_URL;
  const host=request.headers.get("host")?.split(":")[0]||"localhost";
  const protocol=request.headers.get("x-forwarded-proto")==="https"?"wss":"ws";
  return Response.json({webSocketUrl:publicUrl||`${protocol}://${host}:8080/ws/world`},{headers:{"Cache-Control":"no-store"}});
}
