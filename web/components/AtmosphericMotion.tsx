"use client";
import {createContext,useContext,useEffect,useLayoutEffect,useMemo,useRef,type ReactNode,type RefObject} from "react";
import {useFrame} from "@react-three/fiber";
import type {World} from "@/lib/types";
import {EnvironmentClock} from "@/lib/environment-motion";
import {windAt,type WindSample} from "@/lib/wind";

export type AtmosphericFrame={minute:number;seconds:number;weather:string;reducedMotion:boolean;wind:WindSample;advection:{x:number;z:number}};
const fallback={current:{minute:0,seconds:0,weather:"Clear",reducedMotion:true,wind:windAt(0,"Clear"),advection:{x:0,z:0}}};
const AtmosphericMotion=createContext<RefObject<AtmosphericFrame>>(fallback);
// The overview and first-person canvases present the same city. Reopening a view
// must not restart the waves or gusts; retain only a bounded set of city clocks.
const clocks=new Map<string,EnvironmentClock>();
export function useAtmosphericMotion() {return useContext(AtmosphericMotion);}

export default function AtmosphericMotionProvider({world,children}:{world:World;children:ReactNode}) {
  const key=`${world.geography?.version??0}:${world.geography?.centerX??0}:${world.geography?.centerZ??0}`;
  const clock=useMemo(()=>{
    let clock=clocks.get(key);
    if(!clock){clock=new EnvironmentClock();clocks.set(key,clock);if(clocks.size>8)clocks.delete(clocks.keys().next().value!);}
    return clock;
  },[key]);
  const frame=useRef<AtmosphericFrame>({...fallback.current}),reduced=useRef(true),report=useRef(0);
  useLayoutEffect(()=>{clock.ingest(world.minute,world.tickMs??5000,performance.now());},[clock,world.minute,world.tickMs]);
  useEffect(()=>{
    const query=matchMedia("(prefers-reduced-motion: reduce)"),update=()=>{reduced.current=query.matches;};
    update();query.addEventListener("change",update);return()=>query.removeEventListener("change",update);
  },[]);
  useFrame(({gl},delta)=>{
    const now=performance.now(),sampled=clock.sample(now,reduced.current),wind=windAt(reduced.current?0:sampled.minute,world.weather);
    const advection=clock.transport(now,{x:wind.x/10,z:wind.z/10},reduced.current);
    frame.current={...sampled,weather:world.weather,reducedMotion:reduced.current,wind,advection};
    report.current+=delta;
    if(report.current>=.2) {
      report.current=0;const {wind}=frame.current;
      Object.assign(gl.domElement.dataset,{environmentMinute:sampled.minute.toFixed(4),environmentSeconds:sampled.seconds.toFixed(4),windX:wind.x.toFixed(4),windZ:wind.z.toFixed(4),windSpeed:wind.speed.toFixed(4),windStrength:wind.strength.toFixed(4),windMotion:reduced.current?"reduced":"animated"});
    }
  },-3);
  return <AtmosphericMotion.Provider value={frame}>{children}</AtmosphericMotion.Provider>;
}
