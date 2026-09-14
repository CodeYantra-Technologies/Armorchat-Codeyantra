import { createFileRoute } from "@tanstack/react-router";
import { ArmorChatApp } from "@/armorchat/app";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  return <ArmorChatApp />;
}
