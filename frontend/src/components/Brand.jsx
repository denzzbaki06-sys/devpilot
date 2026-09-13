import { Terminal } from "lucide-react";
export default function Brand() {
  return (
    <span className="brand">
      <span className="brand-icon">
        <Terminal size={21} />
      </span>
      DevPilot<span className="brand-dot">.</span>
    </span>
  );
}
