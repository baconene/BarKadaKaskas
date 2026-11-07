<script setup lang="ts">
import AuthLayout from '@/layouts/AuthLayout.vue';
import { Head, Link, useForm } from '@inertiajs/vue3';
import { LoaderCircle } from 'lucide-vue-next';

defineProps<{
    status?: string;
}>();

const form = useForm({
    email: '',
});

const submit = () => {
    form.post(route('password.email'));
};
</script>

<template>
    <AuthLayout eyebrow="Account help" title="Forgot your password?" description="Enter your email and we'll send you a link to reset it.">
        <Head title="Forgot password" />

        <p v-if="status" class="auth-status" role="status">{{ status }}</p>

        <form @submit.prevent="submit" class="auth-form">
            <div class="field">
                <label for="email">Email address</label>
                <input
                    id="email"
                    v-model="form.email"
                    type="email"
                    required
                    autofocus
                    autocomplete="email"
                    placeholder="email@example.com"
                    :aria-invalid="!!form.errors.email"
                    aria-describedby="email-error"
                />
                <p v-if="form.errors.email" id="email-error" class="field-error">{{ form.errors.email }}</p>
            </div>

            <button type="submit" class="btn auth-submit" :disabled="form.processing">
                <LoaderCircle v-if="form.processing" class="h-4 w-4 animate-spin" />
                Email password reset link
            </button>

            <p class="auth-switch">Remembered it? <Link :href="route('login')" class="auth-link">Back to log in</Link></p>
        </form>
    </AuthLayout>
</template>
