'use client';

import { WizardData } from '../SceneWizard';

interface BasicInfoProps {
    data: WizardData;
    updateData: (updates: Partial<WizardData>) => void;
}

const PROJECT_TYPES = [
    { value: 'cinematic_film', label: '🎬 Cinematic Film', desc: 'High production value, moody lighting, wide aspect ratio' },
    { value: 'corporate_video', label: '💼 Corporate Video', desc: 'Clean, professional, bright lighting, informative' },
    { value: 'advertisement', label: '📺 Advertisement', desc: 'Punchy, high energy, product focused' },
    { value: 'comedy_skit', label: '😂 Comedy Skit', desc: 'Bright, character focused, quick cuts' },
    { value: 'cartoon', label: '🎨 Cartoon', desc: 'Stylized, animated, colorful' },
    { value: 'vlog', label: '📹 Vlog', desc: 'Handheld style, personal address, jump cuts' },
    { value: 'podcast', label: '🎙️ Podcast', desc: 'Static shots, focus on dialogue, studio setting' },
    { value: 'music_video', label: '🎵 Music Video', desc: 'Ritmic editing, abstract visuals, performance shots' },
    { value: 'documentary', label: '📖 Documentary', desc: 'Realism, interview setups, b-roll' },
];

export default function BasicInfo({ data, updateData }: BasicInfoProps) {
    const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
        const { name, value } = e.target;
        updateData({
            [name]: name === 'duration' ? parseInt(value) || 0 : value,
        } as any);
    };

    return (
        <div className="space-y-6 animate-fadeIn">
            <div>
                <h3 className="text-xl font-medium text-zinc-900 dark:text-zinc-100 mb-4">
                    Basic Information
                </h3>
                <p className="text-zinc-600 dark:text-zinc-400 mb-6">
                    Let's start with the basics. What kind of project are we building today?
                </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                <div className="col-span-1 md:col-span-2">
                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                        Project Title
                    </label>
                    <input
                        type="text"
                        name="title"
                        value={data.title}
                        onChange={handleChange}
                        className="w-full px-4 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                        placeholder="e.g., The Last Coffee Bean"
                        autoFocus
                    />
                </div>

                <div className="col-span-1 md:col-span-2">
                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                        Project Type
                    </label>
                    <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                        {PROJECT_TYPES.map((type) => (
                            <button
                                key={type.value}
                                onClick={() => updateData({ project_type: type.value })}
                                className={`p-3 rounded-lg border text-left transition-all
                                    ${data.project_type === type.value
                                        ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/30 ring-1 ring-blue-500'
                                        : 'border-zinc-200 dark:border-zinc-700 hover:border-blue-300 dark:hover:border-blue-700'
                                    }`}
                            >
                                <div className="font-medium text-zinc-900 dark:text-zinc-100 mb-1">
                                    {type.label.split(' ')[0]} <span className="text-sm">{type.label.split(' ').slice(1).join(' ')}</span>
                                </div>
                            </button>
                        ))}
                    </div>
                    <p className="mt-2 text-sm text-zinc-500">
                        {PROJECT_TYPES.find(t => t.value === data.project_type)?.desc}
                    </p>
                </div>

                <div>
                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                        Duration (seconds)
                    </label>
                    <input
                        type="number"
                        name="duration"
                        value={data.duration}
                        onChange={handleChange}
                        min="10"
                        className="w-full px-4 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                    />
                </div>
            </div>

            <div>
                <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                    Description / Premise
                </label>
                <textarea
                    name="description"
                    value={data.description}
                    onChange={handleChange}
                    rows={4}
                    className="w-full px-4 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                    placeholder="Describe the main idea, plot, or goal of your video..."
                />
            </div>
        </div>
    );
}
