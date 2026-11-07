<script setup lang="ts">
import AuthLayout from '@/layouts/AuthLayout.vue';
import { Head, useForm } from '@inertiajs/vue3';
import { LoaderCircle } from 'lucide-vue-next';

interface Props {
    token: string;
    email: string;
}

const props = defineProps<Props>();

const form = useForm({
    token: props.token,
    email: props.email,
    password: '',
    password_confirmation: '',
});

const submit = () => {
    form.post(route('password.store'), {
        onFinish: () => {
            form.reset('password', 'password_confirmation');
        },
    });
};
</script>

<template>
    <AuthLayout eyebrow="Account help" title="Set a new password" description="Choose a new password for your account.">
        <Head title="Reset password" />

        <form @submit.prevent="submit" class="auth-form">
            <div class="field">
                <label for="email">Email address</label>
                <input id="email" v-model="form.email" type="email" autocomplete="email" readonly aria-describedby="email-error" />
                <p v-if="form.errors.email" id="email-error" class="field-error">{{ form.errors.email }}</p>
            </div>

            <div class="field">
                <label for="password">New password</label>
                <input
                    id="password"
                    v-model="form.password"
                    type="password"
                    required
                    autofocus
                    autocomplete="new-password"
                    placeholder="New password"
                    :aria-invalid="!!form.errors.password"
                    aria-describedby="password-error"
                />
                <p v-if="form.errors.password" id="password-error" class="field-error">{{ form.errors.password }}</p>
            </div>

            <div class="field">
                <label for="password_confirmation">Confirm new password</label>
                <input
                    id="password_confirmation"
                    v-model="form.password_confirmation"
                    type="password"
                    required
                    autocomplete="new-password"
                    placeholder="Repeat new password"
                    :aria-invalid="!!form.errors.password_confirmation"
                    aria-describedby="password-confirmation-error"
                />
                <p v-if="form.errors.password_confirmation" id="password-confirmation-error" class="field-error">
                    {{ form.errors.password_confirmation }}
                </p>
            </div>

            <button type="submit" class="btn auth-submit" :disabled="form.processing">
                <LoaderCircle v-if="form.processing" class="h-4 w-4 animate-spin" />
                Reset password
            </button>
        </form>
    </AuthLayout>
</template>
