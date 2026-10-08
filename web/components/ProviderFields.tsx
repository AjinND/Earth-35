"use client";
import {useEffect,useState} from "react";
import {api} from "@/lib/api";

type ProviderOption={id:string;label:string;defaultModel:string;keyUrl:string;modelHelp:string;keyHelp:string};
export type HostedConfig={available:boolean;providers:string[];defaultProvider:string;defaultModel:string;providerOptions:ProviderOption[]};

export function useHostedConfig() {
  const [config,setConfig]=useState<HostedConfig|null>(null);
  useEffect(()=>{api<HostedConfig>("/hosted/config").then(setConfig).catch(()=>setConfig({available:false,providers:[],defaultProvider:"nvidia",defaultModel:"",providerOptions:[]}));},[]);
  return config;
}

/** Shared fields keep registration, conversion, and connection updates in sync. */
export default function ProviderFields({config,initialProvider,initialModel,disabled=false}:{config:HostedConfig|null;initialProvider?:string;initialModel?:string;disabled?:boolean}) {
  const [providerId,setProviderId]=useState(initialProvider||config?.defaultProvider||"nvidia");
  const [model,setModel]=useState<string|null>(initialModel??null);
  const provider=config?.providerOptions.find(p=>p.id===providerId);
  if(!config) return <p className="muted" role="status">Loading model providers…</p>;
  if(!config.available) return <p className="error" role="alert">Hosted agents are unavailable on this server. Ask the Earth 35 operator to check its hosted configuration, or use an external runtime.</p>;
  return <>
    <label>AI provider<select name="provider" value={providerId} disabled={disabled} onChange={e=>{
      setProviderId(e.target.value);setModel(null);
      const key=e.currentTarget.form?.elements.namedItem("apiKey");
      if(key instanceof HTMLInputElement) key.value="";
    }}>{config.providerOptions.map(p=><option key={p.id} value={p.id}>{p.label}</option>)}</select></label>
    <label>Model ID<input name="model" required maxLength={120} value={model??provider?.defaultModel??""} disabled={disabled} onChange={e=>setModel(e.target.value)}/><small>{provider?.modelHelp}</small></label>
    <label>Provider API key <span className="private-tag">PRIVATE</span><input name="apiKey" type="password" required maxLength={512} autoComplete="off" disabled={disabled} placeholder={`Paste your ${provider?.label||"provider"} API key`}/><small>{provider?.keyHelp} {provider&&<a href={provider.keyUrl} target="_blank" rel="noreferrer">Get an API key</a>}</small></label>
    <p className="muted">Earth 35 verifies the model connection and encrypts your key on the server. Inference is billed to your selected provider account.</p>
  </>;
}
