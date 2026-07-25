import { useState } from "react";
import { clearToken, getToken } from "./api/client";
import LoginPage from "./pages/LoginPage";
import PolicyListPage from "./pages/PolicyListPage";
import "./App.css";

export default function App() {
  const [loggedIn, setLoggedIn] = useState(!!getToken());

  function handleLogout() {
    clearToken();
    setLoggedIn(false);
  }

  return loggedIn ? (
    <PolicyListPage onLogout={handleLogout} />
  ) : (
    <LoginPage onLoggedIn={() => setLoggedIn(true)} />
  );
}
