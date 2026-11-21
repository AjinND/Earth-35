"use client";
import {useMemo} from "react";
import {useFrame,type ThreeElements} from "@react-three/fiber";
import {marineUniforms,marineShader,updateMarineUniforms} from "@/lib/coastal-water";
import WeatherMaterial from "./WeatherMaterial";
import {useAtmosphericMotion} from "./AtmosphericMotion";

const waterProgramKey=()=>"natural-water-wind-v1";

/** Natural water shares the ocean's wind ripples and the world's bounded clock. */
export default function WindWaterMaterial(props:Omit<ThreeElements["meshStandardMaterial"],"onBeforeCompile"|"customProgramCacheKey">) {
  const motion=useAtmosphericMotion(),uniforms=useMemo(marineUniforms,[]);
  const shader=useMemo(()=>marineShader(uniforms),[uniforms]);
  useFrame(()=>{
    const frame=motion.current;updateMarineUniforms(uniforms,frame);
    uniforms.uSurfRain.value=frame.weather==="Rain"?1:0;
  });
  return <WeatherMaterial {...props} surface="water" onBeforeCompile={shader} customProgramCacheKey={waterProgramKey}/>;
}
