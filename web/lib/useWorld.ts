"use client";
import {useEffect,useState} from "react";
import {api} from "./api";
import type {World} from "./types";
export function useWorld() {
  const [world,setWorld]=useState<World|null>(null),[connection,setConnection]=useState("connecting"),[error,setError]=useState("");
  useEffect(()=>{
    let alive=true,socket:WebSocket|undefined,retry:ReturnType<typeof setTimeout>|undefined;
    const accept=(data:World)=>{if(alive){setWorld(old=>!old||data.version>=old.version?data:old);setError("");}};
    const poll=()=>api<World>("/world").then(data=>{accept(data);if(alive&&socket?.readyState!==WebSocket.OPEN)setConnection("polling");}).catch(e=>{if(alive){setError(e.message);setConnection("offline");}});
    const connect=()=>{
      if(!alive)return;
      fetch("/api/config",{cache:"no-store"}).then(r=>r.json()).then(({webSocketUrl})=>{
        if(!alive)return;
        socket=new WebSocket(webSocketUrl);
        socket.onopen=()=>{if(alive)setConnection("live");};
        socket.onmessage=event=>{try{accept(JSON.parse(event.data));}catch{}};
        socket.onclose=()=>{if(alive){setConnection("reconnecting");retry=setTimeout(connect,4000);}};
        socket.onerror=()=>socket?.close();
      }).catch(()=>{if(alive){setConnection("polling");retry=setTimeout(connect,4000);}});
    };
    poll();connect();const interval=setInterval(poll,5000);
    return()=>{alive=false;clearInterval(interval);clearTimeout(retry);socket?.close();};
  },[]);
  return {world,connection,error};
}
