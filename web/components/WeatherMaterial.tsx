"use client";
import {createContext,useContext,useMemo} from "react";
import type {ThreeElements} from "@react-three/fiber";
import {Color} from "three";

export const SurfaceWeather=createContext("Clear");
export default function WeatherMaterial({surface,color="#ffffff",roughness=1,...props}:ThreeElements["meshStandardMaterial"]&{surface:"ground"|"water"|"building"}) {
  const rain=useContext(SurfaceWeather)==="Rain";
  const tint=useMemo(()=>{
    const tint=new Color(color as string);
    if(rain)surface==="water"?tint.lerp(new Color("#547984"),.35):tint.multiplyScalar(surface==="ground"?.78:.86);
    return tint;
  },[color,rain,surface]);
  return <meshStandardMaterial {...props} color={tint} roughness={rain?(surface==="water"?.6:Number(roughness)*.58):roughness}/>;
}
