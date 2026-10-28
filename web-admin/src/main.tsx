import React from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { AdminApi } from "./api";
import "./style.css";
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App api={new AdminApi()} />
  </React.StrictMode>,
);
