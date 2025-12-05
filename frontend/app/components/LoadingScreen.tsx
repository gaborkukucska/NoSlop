//! START OF FILE frontend/app/components/LoadingScreen.tsx
export default function LoadingScreen() {
    return (
        <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-zinc-50 to-zinc-100 dark:from-zinc-900 dark:to-black">
            <div className="text-center">
                <div className="text-6xl mb-4 animate-pulse">🚫🥣</div>
                <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50 mb-2">
                    NoSlop
                </h2>
                <p className="text-zinc-600 dark:text-zinc-400">Loading...</p>
                <div className="mt-4 flex justify-center">
                    <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
                </div>
            </div>
        </div>
    );
}
