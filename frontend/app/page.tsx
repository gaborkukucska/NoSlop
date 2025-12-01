//! START OF FILE frontend/app/page.tsx
'use client';

import { useState, useEffect } from 'react';
import ChatInterface from './components/ChatInterface';
import PersonalitySelector from './components/PersonalitySelector';

export default function Home() {
  const [healthStatus, setHealthStatus] = useState<any>(null);
  const [showPersonality, setShowPersonality] = useState(false);

  useEffect(() => {
    checkHealth();
  }, []);

  const checkHealth = async () => {
    try {
      const response = await fetch('http://localhost:8000/health');
      const data = await response.json();
      setHealthStatus(data);
    } catch (error) {
      console.error('Health check failed:', error);
      setHealthStatus({ status: 'error', ollama: 'disconnected' });
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-zinc-50 to-zinc-100 dark:from-zinc-900 dark:to-black">
      {/* Header */}
      <header className="bg-white dark:bg-zinc-900 border-b border-zinc-200 dark:border-zinc-800 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-3">
              <div className="text-3xl">🚫🥣</div>
              <div>
                <h1 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50">
                  NoSlop
                </h1>
                <p className="text-sm text-zinc-600 dark:text-zinc-400">
                  AI-Powered Media Creation Studio
                </p>
              </div>
            </div>

            <div className="flex items-center space-x-4">
              {/* Health Status */}
              <div className="flex items-center space-x-2">
                <div
                  className={`w-2 h-2 rounded-full ${healthStatus?.status === 'ok'
                      ? 'bg-green-500'
                      : healthStatus?.status === 'degraded'
                        ? 'bg-yellow-500'
                        : 'bg-red-500'
                    }`}
                />
                <span className="text-sm text-zinc-600 dark:text-zinc-400">
                  {healthStatus?.ollama === 'connected' ? 'Connected' : 'Disconnected'}
                </span>
                {healthStatus?.model_count > 0 && (
                  <span className="text-xs text-zinc-500">
                    ({healthStatus.model_count} models)
                  </span>
                )}
              </div>

              <button
                onClick={() => setShowPersonality(!showPersonality)}
                className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors text-sm font-medium"
              >
                {showPersonality ? 'Hide' : 'Personality'}
              </button>
            </div>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Chat Interface - Takes up 2 columns on large screens */}
          <div className="lg:col-span-2 h-[calc(100vh-200px)]">
            <ChatInterface />
          </div>

          {/* Sidebar */}
          <div className="space-y-6">
            {/* Personality Selector */}
            {showPersonality && <PersonalitySelector />}

            {/* Quick Info */}
            <div className="bg-white dark:bg-zinc-900 rounded-lg shadow-lg p-6">
              <h3 className="text-lg font-semibold text-zinc-900 dark:text-zinc-50 mb-3">
                🎯 Quick Start
              </h3>
              <ul className="space-y-2 text-sm text-zinc-600 dark:text-zinc-400">
                <li className="flex items-start">
                  <span className="mr-2">1️⃣</span>
                  <span>Chat with Admin AI about your project idea</span>
                </li>
                <li className="flex items-start">
                  <span className="mr-2">2️⃣</span>
                  <span>Customize AI personality to match your style</span>
                </li>
                <li className="flex items-start">
                  <span className="mr-2">3️⃣</span>
                  <span>Create projects and let AI guide you</span>
                </li>
              </ul>
            </div>

            {/* System Info */}
            <div className="bg-white dark:bg-zinc-900 rounded-lg shadow-lg p-6">
              <h3 className="text-lg font-semibold text-zinc-900 dark:text-zinc-50 mb-3">
                ⚙️ System Status
              </h3>
              <div className="space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-zinc-600 dark:text-zinc-400">Backend:</span>
                  <span className={healthStatus?.status === 'ok' ? 'text-green-600' : 'text-red-600'}>
                    {healthStatus?.status || 'Unknown'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-zinc-600 dark:text-zinc-400">Ollama:</span>
                  <span className={healthStatus?.ollama === 'connected' ? 'text-green-600' : 'text-red-600'}>
                    {healthStatus?.ollama || 'Unknown'}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-zinc-600 dark:text-zinc-400">ComfyUI:</span>
                  <span className="text-yellow-600">Coming Soon</span>
                </div>
              </div>
            </div>

            {/* About */}
            <div className="bg-gradient-to-br from-blue-50 to-purple-50 dark:from-blue-950 dark:to-purple-950 rounded-lg shadow-lg p-6">
              <h3 className="text-lg font-semibold text-zinc-900 dark:text-zinc-50 mb-2">
                ✨ About NoSlop
              </h3>
              <p className="text-sm text-zinc-700 dark:text-zinc-300">
                Self-hosted, decentralized media creation platform. Own your data, own your creativity.
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                <span className="px-2 py-1 bg-white dark:bg-zinc-800 rounded text-xs">🦙 Ollama</span>
                <span className="px-2 py-1 bg-white dark:bg-zinc-800 rounded text-xs">🎨 ComfyUI</span>
                <span className="px-2 py-1 bg-white dark:bg-zinc-800 rounded text-xs">🎥 FFmpeg</span>
                <span className="px-2 py-1 bg-white dark:bg-zinc-800 rounded text-xs">👁️ OpenCV</span>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

